package io.github.glinte.refactor.operations

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiTreeUtil
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter

internal data class ResolvedSymbol(
    val element: PsiNamedElement,
    val target: JsonObject,
)

internal class SymbolResolver {
    fun resolve(project: Project, params: JsonObject): ResolvedSymbol {
        val selector = params["selector"] as? JsonObject
            ?: throw RefactorException("SYMBOL_NOT_FOUND", 3, "request is missing selector")
        val selectedFile = selector["file"]?.jsonPrimitive?.content
        if (
            selectedFile?.substringAfterLast('.', "")?.equals("py", ignoreCase = true) == true &&
            !PluginManagerCore.isPluginInstalled(PluginId.getId("PythonCore")) &&
            !PluginManagerCore.isPluginInstalled(PluginId.getId("Pythonid"))
        ) {
            throw RefactorException(
                "LANGUAGE_PLUGIN_MISSING",
                4,
                "Python support is not installed in this IntelliJ instance",
            )
        }
        val resolved = selector["symbol"]?.jsonPrimitive?.content?.let {
            resolveQualified(project, it)
        } ?: resolvePosition(project, selector)
        val element = sourceDeclaration(
            (resolved as? PsiMethod)
            ?.takeIf(PsiMethod::isConstructor)
            ?.containingClass
            ?: resolved,
        )
        ensureSupported(element)
        guard(element, selector["expect"]?.jsonPrimitive?.content)
        return ResolvedSymbol(element, describe(project, element))
    }

    fun describe(project: Project, element: PsiNamedElement): JsonObject =
        buildJsonObject {
            put("name", element.name.orEmpty())
            put("qualifiedName", qualifiedName(element))
            put("kind", kind(element))
            put("language", element.language.id)
            element.containingFile?.virtualFile?.let { file ->
                put("path", relativePath(project, file))
                val range = element.textRange
                val document = element.containingFile.viewProvider.document
                if (range != null && document != null) {
                    put("line", document.getLineNumber(range.startOffset) + 1)
                    put("col", range.startOffset - document.getLineStartOffset(
                        document.getLineNumber(range.startOffset),
                    ) + 1)
                }
            }
            if (element.language.id.equals("Python", ignoreCase = true)) {
                put(
                    "warnings",
                    buildJsonArray {
                        add("Dynamic Python references cannot be discovered reliably.")
                    },
                )
            }
        }

    private fun resolveQualified(project: Project, selector: String): PsiNamedElement {
        val className = selector.substringBefore('#')
        val member = selector.substringAfter('#', missingDelimiterValue = "")
        val declaration = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))
            ?: resolveKotlin(project, className)
            ?: resolvePython(project, className)
            ?: throw RefactorException(
                "SYMBOL_NOT_FOUND",
                3,
                "symbol $selector was not found in the project",
            )
        if (member.isEmpty()) return declaration

        val memberName = member.substringBefore('(')
        val requestedDescriptor = member.takeIf { '(' in it }?.substring(memberName.length)
        if (declaration !is PsiClass) {
            val candidates = if (declaration is KtClassOrObject) {
                declaration.declarations
                    .filterIsInstance<KtNamedDeclaration>()
                    .filter { it.name == memberName }
            } else {
                PsiTreeUtil.collectElementsOfType(declaration, PsiNamedElement::class.java)
                    .filter { candidate ->
                        candidate !== declaration &&
                            candidate.name == memberName &&
                            PsiTreeUtil.getParentOfType(
                                candidate.parent,
                                PsiNamedElement::class.java,
                                false,
                            ) === declaration
                    }
            }
            return when (candidates.size) {
                0 -> throw RefactorException(
                    "SYMBOL_NOT_FOUND",
                    3,
                    "member $memberName was not found on $className",
                )
                1 -> candidates.single()
                else -> throw RefactorException(
                    "AMBIGUOUS_TARGET",
                    3,
                    "$selector matches more than one Kotlin member; select it by position",
                    buildJsonObject {
                        put("candidates", buildJsonArray {
                            candidates.forEach { add(describe(project, it)) }
                        })
                    },
                )
            }
        }
        val psiClass = declaration
        val methods = psiClass.findMethodsByName(memberName, true)
        val matchingMethods = methods.filter {
            requestedDescriptor == null || methodDescriptor(it) == requestedDescriptor
        }
        val selectedMethods = if (
            requestedDescriptor == null &&
            matchingMethods.isNotEmpty() &&
            matchingMethods.map(::methodDescriptor).distinct().size == 1
        ) {
            listOf(matchingMethods.firstOrNull { it.containingClass == psiClass } ?: matchingMethods.first())
        } else {
            matchingMethods
        }
        val candidates = buildList<PsiNamedElement> {
            addAll(selectedMethods.map(::sourceDeclaration))
            psiClass.findFieldByName(memberName, true)?.let { add(sourceDeclaration(it)) }
            psiClass.innerClasses.filter { it.name == memberName }.mapTo(this, ::sourceDeclaration)
        }.distinct()
        return when (candidates.size) {
            0 -> throw RefactorException(
                "SYMBOL_NOT_FOUND",
                3,
                if (requestedDescriptor != null && methods.isNotEmpty()) {
                    "descriptor $requestedDescriptor did not match $memberName; available descriptors: " +
                        methods.joinToString { methodDescriptor(it) }
                } else {
                    "member $memberName was not found on $className"
                },
            )
            1 -> candidates.single()
            else -> throw RefactorException(
                "AMBIGUOUS_TARGET",
                3,
                "$selector matches more than one member; include a JVM descriptor",
                buildJsonObject {
                    put(
                        "candidates",
                        buildJsonArray {
                            candidates.forEach { add(describe(project, it)) }
                        },
                    )
                },
            )
        }
    }

    private fun sourceDeclaration(element: PsiNamedElement): PsiNamedElement =
        (element.navigationElement as? PsiNamedElement)?.takeIf { it !== element } ?: element

    private fun resolveKotlin(project: Project, qualifiedName: String): KtNamedDeclaration? {
        val scope = GlobalSearchScope.projectScope(project)
        val matches = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope).asSequence()
            .mapNotNull { com.intellij.psi.PsiManager.getInstance(project).findFile(it) as? KtFile }
            .flatMap { file ->
                PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java).asSequence()
            }
            .filter { it.fqName?.asString() == qualifiedName }
            .toList()
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> throw RefactorException(
                "AMBIGUOUS_TARGET",
                3,
                "$qualifiedName matches more than one Kotlin declaration",
                buildJsonObject {
                    put("candidates", buildJsonArray {
                        matches.forEach { add(describe(project, it)) }
                    })
                },
            )
        }
    }

    private fun resolvePython(project: Project, qualifiedName: String): PsiNamedElement? {
        val pythonType = FileTypeManager.getInstance().getFileTypeByExtension("py")
        if (pythonType.name.equals("UNKNOWN", ignoreCase = true)) return null
        val matches = FileTypeIndex.getFiles(
            pythonType,
            GlobalSearchScope.projectScope(project),
        ).asSequence()
            .mapNotNull { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }
            .filter { it.language.id.equals("Python", ignoreCase = true) }
            .flatMap { file ->
                PsiTreeUtil.collectElementsOfType(file, PsiNamedElement::class.java).asSequence()
            }
            .filter {
                val candidate = pythonQualifiedName(project, it)
                candidate == qualifiedName ||
                    candidate.endsWith(".$qualifiedName") ||
                    qualifiedName.endsWith(".$candidate")
            }
            .toList()
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> throw RefactorException(
                "AMBIGUOUS_TARGET",
                3,
                "$qualifiedName matches more than one Python declaration",
                buildJsonObject {
                    put("candidates", buildJsonArray {
                        matches.forEach { add(describe(project, it)) }
                    })
                },
            )
        }
    }

    private fun methodDescriptor(method: PsiMethod): String = buildString {
        append('(')
        method.parameterList.parameters.forEach { append(typeDescriptor(it.type)) }
        append(')')
        append(method.returnType?.let(::typeDescriptor) ?: "V")
    }

    private fun typeDescriptor(type: PsiType): String = when (type) {
        is PsiPrimitiveType -> when (type.canonicalText) {
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "void" -> "V"
            else -> error("unknown primitive type: ${type.canonicalText}")
        }
        is PsiArrayType -> "[${typeDescriptor(type.componentType)}"
        is PsiClassType -> {
            val qualifiedName = type.resolve()?.qualifiedName
                ?: type.canonicalText.substringBefore('<')
            "L${qualifiedName.replace('.', '/')};"
        }
        else -> "L${type.canonicalText.substringBefore('<').replace('.', '/')};"
    }

    private fun resolvePosition(project: Project, selector: JsonObject): PsiNamedElement {
        val pathText = selector["file"]?.jsonPrimitive?.content
            ?: throw RefactorException("SYMBOL_NOT_FOUND", 3, "position selector is missing file")
        val line = selector["line"]?.jsonPrimitive?.intOrNull
            ?: throw RefactorException("POSITION_OUT_OF_RANGE", 3, "position selector is missing line")
        val col = selector["col"]?.jsonPrimitive?.intOrNull
            ?: throw RefactorException("POSITION_OUT_OF_RANGE", 3, "position selector is missing col")
        val root = Path.of(project.basePath ?: error("project has no base path"))
        val requestedPath = runCatching { Path.of(pathText) }
            .getOrElse {
                throw RefactorException("FILE_NOT_FOUND", 3, "file path is invalid: $pathText")
            }
        val path = requestedPath.let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: if (!requestedPath.isAbsolute) {
                val normalized = pathText.replace('\\', '/').trimStart('/')
                findContentFile(project, normalized)
            } else {
                null
            }
            ?: throw RefactorException("FILE_NOT_FOUND", 3, "file $pathText was not found")
        if (!ProjectRootManager.getInstance(project).fileIndex.isInContent(file)) {
            throw RefactorException("FILE_NOT_FOUND", 3, "file $pathText is outside project content")
        }
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file)
            ?: throw RefactorException("UNSUPPORTED_SYMBOL", 3, "$pathText has no PSI representation")
        val document = psiFile.viewProvider.document
            ?: throw RefactorException("UNSUPPORTED_SYMBOL", 3, "$pathText has no document")
        if (line !in 1..document.lineCount) {
            throw RefactorException(
                "POSITION_OUT_OF_RANGE",
                3,
                "line $line is outside $pathText (${document.lineCount} lines)",
            )
        }
        val lineIndex = line - 1
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val offset = lineStart + col - 1
        if (col < 1 || offset > lineEnd) {
            throw RefactorException(
                "POSITION_OUT_OF_RANGE",
                3,
                "column $col is outside line $line of $pathText",
            )
        }
        return resolveAt(psiFile, offset)
            ?: throw RefactorException(
                "SYMBOL_NOT_FOUND",
                3,
                "no named symbol resolves at $pathText:$line:$col",
            )
    }

    private fun findContentFile(
        project: Project,
        requestedRelativePath: String,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val matches = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
        ProjectRootManager.getInstance(project).fileIndex.iterateContent { file ->
            if (
                !file.isDirectory &&
                file.path.replace('\\', '/').endsWith("/$requestedRelativePath")
            ) {
                matches += file
            }
            true
        }
        val exactPaths = ProjectRootManager.getInstance(project).contentRoots.map { root ->
            "${root.path.trimEnd('/')}/$requestedRelativePath"
        }
        val exactMatches = matches.filter { match ->
            exactPaths.any { expected ->
                match.path.equals(
                    expected,
                    ignoreCase = System.getProperty("os.name")
                        .startsWith("Windows", ignoreCase = true),
                )
            }
        }
        val candidates = exactMatches.ifEmpty { matches }
        return when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            else -> throw RefactorException(
                "AMBIGUOUS_TARGET",
                3,
                "file $requestedRelativePath matches more than one project content path",
                buildJsonObject {
                    put(
                        "candidates",
                        buildJsonArray {
                            candidates
                                .map { relativePath(project, it) }
                                .sorted()
                                .forEach(::add)
                        },
                    )
                },
            )
        }
    }

    private fun resolveAt(file: PsiFile, offset: Int): PsiNamedElement? {
        val reference: PsiReference? = file.findReferenceAt(offset)
        val resolved = reference?.resolve()
        if (resolved is PsiNamedElement) return resolved
        val leaf = file.findElementAt(offset) ?: if (offset > 0) file.findElementAt(offset - 1) else null
        return PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
    }

    private fun guard(element: PsiNamedElement, expectation: String?) {
        if (expectation == null) return
        val expectedName = expectation.substringBeforeLast(':')
        val expectedKind = expectation.substringAfterLast(':', missingDelimiterValue = "")
        val actualName = element.name.orEmpty()
        val actualKind = kind(element)
        if (actualName != expectedName || (expectedKind.isNotEmpty() && actualKind != expectedKind.uppercase())) {
            throw RefactorException(
                "TARGET_MISMATCH",
                3,
                "expected $expectation but resolved $actualName:$actualKind",
                buildJsonObject {
                    put("expected", expectation)
                    put("actualName", actualName)
                    put("actualKind", actualKind)
                },
            )
        }
    }

    private fun ensureSupported(element: PsiNamedElement) {
        val supported = when {
            element.language.id.equals("JAVA", ignoreCase = true) ->
                element is PsiClass ||
                    element is PsiMethod ||
                    element is PsiVariable ||
                    element is PsiTypeParameter
            element.language.id.equals("kotlin", ignoreCase = true) ->
                element is KtClassOrObject ||
                    element is KtNamedFunction ||
                    element is KtProperty ||
                    element is KtParameter ||
                    element is KtTypeAlias ||
                    element is KtTypeParameter
            element.language.id.equals("Python", ignoreCase = true) -> {
                val className = element.javaClass.simpleName
                "Class" in className ||
                    "Function" in className ||
                    "Parameter" in className ||
                    "TargetExpression" in className
            }
            else -> false
        }
        if (!supported) {
            throw RefactorException(
                "UNSUPPORTED_SYMBOL",
                3,
                "${element.javaClass.simpleName} in ${element.language.id} is not a supported rename target",
            )
        }
    }

    private fun qualifiedName(element: PsiNamedElement): String =
        (element as? KtNamedDeclaration)?.fqName?.asString()
            ?: if (element.language.id.equals("Python", ignoreCase = true)) {
                pythonQualifiedName(element.project, element)
            } else {
                (element as? PsiQualifiedNamedElement)?.qualifiedName
            }
            ?: when (element) {
                is PsiMethod -> "${element.containingClass?.qualifiedName}#${element.name}"
                is PsiField -> "${element.containingClass?.qualifiedName}#${element.name}"
                else -> element.name.orEmpty()
            }

    private fun pythonQualifiedName(project: Project, element: PsiNamedElement): String {
        val virtualFile = element.containingFile?.virtualFile ?: return element.name.orEmpty()
        val relative = runCatching { Path.of(relativePath(project, virtualFile)) }
            .getOrDefault(Path.of(virtualFile.name))
        val moduleParts = relative.map(Path::toString).toMutableList()
        if (moduleParts.isNotEmpty()) {
            val last = moduleParts.removeLast().substringBeforeLast('.')
            if (last != "__init__") moduleParts.add(last)
        }
        val containers = generateSequence(element.parent) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .filterNot { it is PsiFile }
            .filter { it.language.id.equals("Python", ignoreCase = true) }
            .mapNotNull(PsiNamedElement::getName)
            .toList()
            .asReversed()
        return (moduleParts + containers + listOfNotNull(element.name))
            .filter(String::isNotEmpty)
            .joinToString(".")
    }

    private fun kind(element: PsiNamedElement): String {
        if (element.language.id.equals("Python", ignoreCase = true)) {
            val className = element.javaClass.simpleName
            return when {
                "Class" in className -> "CLASS"
                "Function" in className -> "FUNCTION"
                "Parameter" in className || "NamedParameter" in className -> "PARAMETER"
                "TargetExpression" in className -> "VARIABLE"
                else -> "PYTHON_SYMBOL"
            }
        }
        return when (element) {
        is KtObjectDeclaration -> if (element.isCompanion()) "COMPANION_OBJECT" else "OBJECT"
        is KtClass -> if (element.isInterface()) "INTERFACE" else "CLASS"
        is KtNamedFunction -> "FUNCTION"
        is KtProperty -> if (element.isLocal) "LOCAL" else "PROPERTY"
        is KtParameter -> if (element.hasValOrVar()) "PROPERTY" else "PARAMETER"
        is KtTypeAlias -> "TYPE_ALIAS"
        is KtTypeParameter -> "TYPE_PARAMETER"
        is PsiTypeParameter -> "TYPE_PARAMETER"
        is PsiClass -> when {
            element.isAnnotationType -> "ANNOTATION"
            element.isEnum -> "ENUM"
            element.isRecord -> "RECORD"
            element.isInterface -> "INTERFACE"
            else -> "CLASS"
        }
        is PsiMethod -> if (element.isConstructor) "CONSTRUCTOR" else "METHOD"
        is PsiField -> if (element is com.intellij.psi.PsiEnumConstant) "ENUM_CONSTANT" else "FIELD"
        is PsiParameter -> "PARAMETER"
        is PsiVariable -> "LOCAL"
        else -> element.javaClass.simpleName
            .removePrefix("Psi")
            .removeSuffix("Impl")
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .uppercase()
        }
    }

    private fun relativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val root = project.basePath
        val baseRelative = root?.let {
            runCatching {
                Path.of(it).relativize(Path.of(file.path)).toString().replace('\\', '/')
            }.getOrNull()
        }
        if (baseRelative != null && !baseRelative.startsWith("../") && !baseRelative.startsWith('/')) {
            return baseRelative
        }
        val contentRoot = ProjectRootManager.getInstance(project).fileIndex
            .getContentRootForFile(file)
        return contentRoot
            ?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it, '/') }
            ?: baseRelative
            ?: file.path
    }
}
