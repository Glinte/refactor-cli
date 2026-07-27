package io.github.glinte.refactor.operations

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.SuperMethodsSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.search.PyClassInheritorsSearch
import com.jetbrains.python.psi.types.TypeEvalContext

internal class RenameService(
    private val resolver: SymbolResolver,
    private val usageService: UsageService,
    private val executeRename: (Project, PsiNamedElement, String) -> Unit =
        { project, element, newName ->
            NonInteractiveRenameProcessor(project, element, newName).run()
        },
) {
    private data class FileChange(
        val file: VirtualFile?,
        val oldPath: String?,
        val newPath: String?,
        val before: ByteArray?,
        val after: ByteArray?,
        val expected: Boolean,
        val regions: List<IntRange>,
        val charset: Charset,
    )

    fun rename(
        project: Project,
        params: JsonObject,
        symbol: ResolvedSymbol,
        syncMs: Long,
    ): JsonObject =
        locks.computeIfAbsent(project) { ReentrantLock() }.withLock {
            renameLocked(project, params, symbol, syncMs)
        }

    private fun renameLocked(
        project: Project,
        params: JsonObject,
        symbol: ResolvedSymbol,
        syncMs: Long,
    ): JsonObject {
        val analysisStarted = System.nanoTime()
        val newName = params["to"]?.jsonPrimitive?.contentOrNull
            ?: throw RefactorException("INVALID_NAME", 3, "rename requires a non-empty new name")
        val diffMode = params["diff"]?.jsonPrimitive?.contentOrNull ?: "none"
        if (diffMode !in diffModes) {
            throw RefactorException(
                "INVALID_ARGUMENT",
                3,
                "diff must be one of: none, inline, file",
            )
        }
        val hierarchy = blockingReadAction {
            hierarchyRoot(project, symbol.element)
                ?.takeIf { it != symbol.element }
                ?.let { it to resolver.describe(project, it) }
        }
        val effectiveSymbol = hierarchy?.let { ResolvedSymbol(it.first, it.second) } ?: symbol
        val hierarchyRootTarget = hierarchy?.second
        val hierarchyMembers = blockingReadAction {
            hierarchyMembers(project, effectiveSymbol.element)
        }
        val analyzedSymbols = blockingReadAction {
            linkedSetOf(effectiveSymbol.element)
                .apply { addAll(hierarchyMembers) }
                .map { element -> ResolvedSymbol(element, resolver.describe(project, element)) }
        }
        val analysisModificationStamp = PsiModificationTracker.getInstance(project).modificationCount
        validateName(project, effectiveSymbol.element, newName)
        val analysis = blockingReadAction {
            usageService.analyze(project, analyzedSymbols, Int.MAX_VALUE)
        }
        val affected = blockingReadAction {
            linkedSetOf<VirtualFile>().also { files ->
                effectiveSymbol.element.containingFile?.virtualFile?.let(files::add)
                hierarchyMembers.mapNotNullTo(files) { it.containingFile?.virtualFile }
                analysis.records.mapTo(files, UsageRecord::file)
            }
        }
        val predictedFiles = affected.toSet()
        val conflicts = findConflicts(
            project,
            effectiveSymbol.element,
            hierarchyMembers,
            newName,
        )
        if (PsiModificationTracker.getInstance(project).modificationCount != analysisModificationStamp) {
            throw RefactorException(
                "EXTERNAL_CHANGE_CONFLICT",
                4,
                "the project PSI changed during rename analysis; retry the operation",
            )
        }

        val dryRun = params["dryRun"]?.jsonPrimitive?.booleanOrNull == true
        val forceNonSource = params["forceNonSource"]?.jsonPrimitive?.booleanOrNull == true
        if (dryRun || conflicts.isNotEmpty() || (analysis.nonSourceFiles.isNotEmpty() && !forceNonSource)) {
            val reason = if (conflicts.isNotEmpty()) {
                "rename has conflicts and was not applied"
            } else if (dryRun) {
                "rename was requested as a dry run"
            } else {
                "rename affects non-source files; rerun with --force-non-source"
            }
            throw RefactorException(
                "NEEDS_REVIEW",
                2,
                reason,
                reviewReport(project, symbol, newName, affected, analysis, conflicts, reason),
            )
        }
        checkWritableAndClean(affected)

        val oldTarget = symbol.target
        val before = linkedMapOf<VirtualFile, Pair<String, ByteArray>>()
        val created = linkedSetOf<VirtualFile>()
        affected.forEach { file ->
            before[file] = file.path to file.inputStream.use { stream -> stream.readBytes() }
        }
        verifyUnchanged(project, effectiveSymbol, before)
        val label = "refactor req_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        LocalHistory.getInstance().putSystemLabel(project, label)
        val contentRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
            .contentRoots
            .toList()
        val observer = Disposer.newDisposable("refactor-cli change observer")
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (
                        file !in before &&
                        file !in created &&
                        contentRoots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
                    ) {
                        if (FileDocumentManager.getInstance().isDocumentUnsaved(event.document)) {
                            throw RefactorException(
                                "DIRTY_AFFECTED_DOCUMENT",
                                4,
                                "${file.path} has unsaved IDE changes",
                            )
                        }
                        before[file] = file.path to event.document.text.toByteArray(file.charset)
                    }
                }
            },
            observer,
        )
        ApplicationManager.getApplication().messageBus.connect(observer).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    events.asSequence()
                        .filterNot { it is VFileCreateEvent }
                        .mapNotNull(VFileEvent::getFile)
                        .forEach { file ->
                        if (
                            file !in before &&
                            file !in created &&
                            file.isValid &&
                            !file.isDirectory &&
                            contentRoots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
                        ) {
                            runCatching {
                                before[file] = file.path to file.inputStream.use { it.readBytes() }
                            }
                        }
                    }
                }

                override fun after(events: List<VFileEvent>) {
                    events.mapNotNull(VFileEvent::getFile).forEach { file ->
                        if (
                            file !in before &&
                            file.isValid &&
                            !file.isDirectory &&
                            contentRoots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
                        ) {
                            created.add(file)
                        }
                    }
                }
            },
        )
        val analysisMs = elapsedMs(analysisStarted)
        val mutationStarted = System.nanoTime()
        var observing = true
        try {
            val application = ApplicationManager.getApplication()
            val action = {
                val hierarchy = linkedSetOf(effectiveSymbol.element).apply {
                    addAll(hierarchyMembers.filter { it.isValid && it.name != newName })
                }
                if (hierarchy.size == 1) {
                    executeRename(project, effectiveSymbol.element, newName)
                } else {
                    NonInteractiveRenameProcessor(
                        project,
                        effectiveSymbol.element,
                        newName,
                    ).apply {
                        hierarchy
                            .filter { it != effectiveSymbol.element }
                            .forEach { addElement(it, newName) }
                    }.run()
                }
            }
            if (application.isDispatchThread) action() else application.invokeAndWait(action)
            Disposer.dispose(observer)
            observing = false

            ApplicationManager.getApplication().invokeAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                before.keys.forEach { file ->
                    FileDocumentManager.getInstance().getCachedDocument(file)?.let {
                        FileDocumentManager.getInstance().saveDocument(it)
                    }
                }
            }
            val changed = before.mapNotNull { (file, previous) ->
            val newPath = file.path.takeIf { file.isValid }
            val after = newPath?.let {
                runCatching { file.inputStream.use { stream -> stream.readBytes() } }.getOrNull()
            }
            val charset = runCatching { file.charset }.getOrDefault(Charsets.UTF_8)
            if (
                after == null ||
                newPath != previous.first ||
                !previous.second.contentEquals(after)
            ) {
                FileChange(
                    file = file.takeIf(VirtualFile::isValid),
                    oldPath = previous.first,
                    newPath = newPath,
                    before = previous.second,
                    after = after,
                    expected = file in predictedFiles,
                    regions = changedRegions(previous.second, after ?: ByteArray(0), charset),
                    charset = charset,
                )
            } else {
                null
            }
        }.toMutableList()
        created.filter { it !in before && it.isValid && !it.isDirectory }.forEach { file ->
            val after = runCatching { file.inputStream.use { it.readBytes() } }.getOrNull()
            changed += FileChange(
                file = file,
                oldPath = null,
                newPath = file.path,
                before = null,
                after = after,
                expected = false,
                regions = changedRegions(ByteArray(0), after ?: ByteArray(0), file.charset),
                charset = file.charset,
            )
        }
        val renamedPaths = changed.filter { change ->
            change.oldPath != null &&
                change.newPath != null &&
                change.oldPath != change.newPath
        }
        val refreshedTarget = blockingReadAction {
            resolver.describe(project, symbol.element)
        }
        val diff = buildDiffResult(
            project,
            diffMode,
            changed,
        )

            return buildJsonObject {
            put("status", "APPLIED")
            put(
                "target",
                buildJsonObject {
                    put("oldQualifiedName", oldTarget["qualifiedName"] ?: oldTarget)
                    put("newQualifiedName", refreshedTarget["qualifiedName"] ?: refreshedTarget)
                },
            )
            put("hierarchyRoot", hierarchyRootTarget ?: JsonNull)
            put(
                "renamedPaths",
                buildJsonArray {
                    renamedPaths.forEach { change ->
                        add(buildJsonObject {
                            put(
                                "from",
                                oldRelative(project, change.file, change.oldPath!!, change.newPath),
                            )
                            put(
                                "to",
                                relative(project, change.newPath!!, change.file),
                            )
                        })
                    }
                },
            )
            put(
                "changedFiles",
                buildJsonArray {
                    changed.forEach { change ->
                        val path = change.newPath ?: change.oldPath.orEmpty()
                        val displayPath = change.newPath?.let {
                            relative(project, it, change.file)
                        } ?: oldRelative(project, null, path, null)
                        val extension = java.nio.file.Path.of(path).fileName
                            .toString()
                            .substringAfterLast('.', "")
                            .lowercase()
                        add(buildJsonObject {
                            put("path", displayPath)
                            put("kind", if (extension in sourceExtensions) "SOURCE" else "NON_SOURCE")
                            put("expected", change.expected)
                            put("editCount", change.regions.size)
                            if (change.after == null) put("deleted", true)
                            if (change.before == null) put("created", true)
                            put(
                                "regions",
                                buildJsonArray {
                                    change.regions.take(20).forEach { region ->
                                        add(buildJsonArray {
                                            add(region.first)
                                            add(region.last)
                                        })
                                    }
                                },
                            )
                            if (change.regions.size > 20) put("regionsTruncated", true)
                        })
                    }
                },
            )
            put("localHistoryLabel", label)
            put(
                "warnings",
                buildJsonArray {
                    if (symbol.element.language.id.equals("Python", ignoreCase = true)) {
                        add(PYTHON_WARNING)
                    }
                },
            )
            put(
                "timings",
                buildJsonObject {
                    put("syncMs", syncMs)
                    put("analysisMs", analysisMs)
                    put("mutationMs", elapsedMs(mutationStarted))
                },
            )
            put("diff", diff)
            }
        } catch (error: Throwable) {
            if (observing) {
                runCatching { Disposer.dispose(observer) }
                observing = false
            }
            rollbackOrFail(project, before, created, label, error)
        } finally {
            if (observing) runCatching { Disposer.dispose(observer) }
        }
    }

    private fun verifyUnchanged(
        project: Project,
        symbol: ResolvedSymbol,
        snapshots: Map<VirtualFile, Pair<String, ByteArray>>,
    ) {
        val targetChanged = blockingReadAction {
            !symbol.element.isValid || resolver.describe(project, symbol.element) != symbol.target
        }
        val changedFiles = snapshots.mapNotNull { (file, snapshot) ->
            val unchanged = file.isValid &&
                file.path == snapshot.first &&
                runCatching {
                    file.inputStream.use { it.readBytes() }.contentEquals(snapshot.second)
                }.getOrDefault(false)
            snapshot.first.takeUnless { unchanged }
        }
        if (targetChanged || changedFiles.isNotEmpty()) {
            throw RefactorException(
                "EXTERNAL_CHANGE_CONFLICT",
                4,
                "the target or an affected file changed after rename analysis",
                buildJsonObject {
                    put("targetChanged", targetChanged)
                    put("changedFiles", buildJsonArray { changedFiles.sorted().forEach(::add) })
                },
            )
        }
    }

    private fun hierarchyRoot(
        project: Project,
        element: PsiNamedElement,
    ): PsiNamedElement? {
        if (element is PyFunction) {
            val hierarchy = pythonHierarchy(project, element)
            if (hierarchy.size == 1) return null
            val context = TypeEvalContext.codeInsightFallback(project)
            return hierarchy.filter { function ->
                function.containingClass?.getAncestorClasses(context).orEmpty()
                    .mapNotNull { ancestor ->
                        ancestor.findMethodByName(psiName(function), false, context)
                    }
                    .none(hierarchy::contains)
            }.minByOrNull { function ->
                resolver.describe(project, function)["qualifiedName"].toString()
            } ?: hierarchy.first()
        }
        val method = lightMethod(element) ?: return null
        val hierarchy = methodHierarchy(method)
        if (hierarchy.size == 1) return null
        val root = hierarchy
            .filter { candidate ->
                SuperMethodsSearch.search(candidate, null, true, false)
                    .findAll()
                    .map { it.method }
                    .none(hierarchy::contains)
            }
            .minByOrNull { "${it.containingClass?.qualifiedName}#${it.name}" }
            ?: hierarchy.first()
        return (root.navigationElement as? PsiNamedElement) ?: root
    }

    private fun pythonHierarchy(
        project: Project,
        element: PyFunction,
    ): Set<PyFunction> {
        if (element.containingClass == null) return setOf(element)
        val context = TypeEvalContext.codeInsightFallback(project)
        val hierarchy = linkedSetOf(element)
        val pending = ArrayDeque<PyFunction>().apply { add(element) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val owner = current.containingClass ?: continue
            val related = buildList {
                owner.getAncestorClasses(context).mapNotNullTo(this) { ancestor ->
                    ancestor.findMethodByName(psiName(current), false, context)
                }
                PyClassInheritorsSearch.search(owner, true).findAll()
                    .mapNotNullTo(this) { inheritor ->
                        inheritor.findMethodByName(psiName(current), false, context)
                    }
            }
            related.forEach { function ->
                if (hierarchy.add(function)) pending.add(function)
            }
        }
        return hierarchy
    }

    private fun methodHierarchy(element: PsiMethod): Set<PsiMethod> {
        val hierarchy = linkedSetOf(element)
        val pending = ArrayDeque<PsiMethod>().apply { add(element) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val related = buildList {
                SuperMethodsSearch.search(current, null, true, false)
                    .findAll()
                    .mapTo(this) { it.method }
                addAll(OverridingMethodsSearch.search(current).findAll())
            }
            related.forEach { method ->
                if (hierarchy.add(method)) pending.add(method)
            }
        }
        return hierarchy
    }

    private fun hierarchyMembers(
        project: Project,
        element: PsiNamedElement,
    ): Set<PsiNamedElement> {
        if (element is PyFunction) {
            return pythonHierarchy(project, element)
        }
        val method = lightMethod(element) ?: return emptySet()
        return methodHierarchy(method).mapTo(linkedSetOf()) { member ->
            (member.navigationElement as? PsiNamedElement) ?: member
        }
    }

    private fun lightMethod(element: PsiNamedElement): PsiMethod? = when (element) {
        is PsiMethod -> element
        is KtNamedFunction -> element.toLightMethods().firstOrNull()
        else -> null
    }

    private fun psiName(element: PsiNamedElement): String = element.name.orEmpty()

    private fun rollbackOrFail(
        project: Project,
        before: Map<VirtualFile, Pair<String, ByteArray>>,
        created: Collection<VirtualFile>,
        label: String,
        originalError: Throwable,
    ): Nothing {
        val rollbackErrors = mutableListOf<String>()
        val restoredFiles = mutableMapOf<String, VirtualFile>()
        runCatching {
            ApplicationManager.getApplication().invokeAndWait {
                val restoredDocuments = mutableListOf<com.intellij.openapi.editor.Document>()
                WriteAction.run<RuntimeException> {
                    created.filter(VirtualFile::isValid).forEach { file ->
                        runCatching { file.delete(this) }
                            .onFailure { rollbackErrors += "${file.path}: ${it.message}" }
                    }
                    before.forEach { (file, snapshot) ->
                        runCatching {
                            val oldName = java.nio.file.Path.of(snapshot.first).fileName.toString()
                            if (file.isValid) {
                                if (file.name != oldName) file.rename(this, oldName)
                                val document = FileDocumentManager.getInstance()
                                    .getCachedDocument(file)
                                if (document != null) {
                                    document.setText(snapshot.second.toString(file.charset))
                                    PsiDocumentManager.getInstance(project).commitDocument(document)
                                    restoredDocuments.add(document)
                                } else {
                                    file.setBinaryContent(snapshot.second)
                                }
                            } else {
                                val oldParent = java.nio.file.Path.of(snapshot.first).parent
                                    ?: error("${snapshot.first} has no parent directory")
                                val parent = LocalFileSystem.getInstance()
                                    .refreshAndFindFileByNioFile(oldParent)
                                    ?: error("$oldParent is not available in the VFS")
                                parent.createChildData(this, oldName).also { restored ->
                                    restored.setBinaryContent(snapshot.second)
                                    restoredFiles[snapshot.first] = restored
                                }
                            }
                        }.onFailure { rollbackErrors += "${snapshot.first}: ${it.message}" }
                    }
                }
                restoredDocuments.forEach {
                    FileDocumentManager.getInstance().saveDocument(it)
                }
            }
        }.onFailure { rollbackErrors += it.message ?: it.javaClass.simpleName }
        before.forEach { (file, snapshot) ->
            val restored = file.takeIf { it.isValid && it.path == snapshot.first }
                ?: restoredFiles[snapshot.first]
            val matches = restored?.let {
                runCatching {
                    it.inputStream.use { stream -> stream.readBytes() }
                        .contentEquals(snapshot.second)
                }.getOrDefault(false)
            } == true
            if (!matches) rollbackErrors += "${snapshot.first}: restored content could not be verified"
        }
        created.filter(VirtualFile::isValid).forEach { file ->
            rollbackErrors += "${file.path}: created file still exists after rollback"
        }

        val originalMessage = originalError.message ?: originalError.javaClass.simpleName
        if (rollbackErrors.isEmpty()) {
            generateSequence(originalError) { it.cause }
                .filterIsInstance<RefactorException>()
                .firstOrNull()
                ?.let { throw it }
            throw RefactorException(
                "REFACTORING_FAILED",
                5,
                "IntelliJ could not complete the rename; all captured files were restored: $originalMessage",
                buildJsonObject {
                    put("localHistoryLabel", label)
                    put("rolledBack", true)
                },
            )
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("refactor-cli")
            .createNotification(
                "refactor-cli rollback failed",
                "The workspace may be inconsistent. Check Git status and Local History label $label.",
                NotificationType.ERROR,
            )
            .notify(project)
        throw RefactorException(
            "ROLLBACK_FAILED",
            5,
            "rename failed and rollback was incomplete: $originalMessage",
            buildJsonObject {
                put("localHistoryLabel", label)
                put("rollbackErrors", buildJsonArray { rollbackErrors.forEach(::add) })
                put(
                    "observedFiles",
                    buildJsonArray { before.values.map { it.first }.sorted().forEach(::add) },
                )
            },
        )
    }

    private fun validateName(project: Project, element: PsiNamedElement, name: String) {
        val valid = blockingReadAction {
            RenameUtil.isValidName(project, element, name)
        }
        if (!valid) {
            throw RefactorException("INVALID_NAME", 3, "$name is not a valid identifier")
        }
    }

    private fun findConflicts(
        project: Project,
        element: PsiNamedElement,
        hierarchyMembers: Set<PsiNamedElement>,
        newName: String,
    ): List<String> {
        if (element is PsiMethod) {
            return blockingReadAction {
                val hierarchy = linkedSetOf(element).apply {
                    addAll(OverridingMethodsSearch.search(element).findAll())
                }
                hierarchy.flatMap { method ->
                    method.containingClass
                        ?.findMethodsByName(newName, false)
                        ?.filter { candidate ->
                            candidate !in hierarchy &&
                                candidate.parameterList.parameters
                                    .map { it.type.canonicalText } ==
                                method.parameterList.parameters.map { it.type.canonicalText }
                        }
                        ?.map { candidate ->
                            val owner = candidate.containingClass?.qualifiedName
                                ?: candidate.containingClass?.name
                                ?: "the containing class"
                            "$owner already declares ${candidate.name} with the same parameter types"
                        }
                        .orEmpty()
                }.distinct().sorted()
            }
        }
        if (element.language.id.equals("kotlin", ignoreCase = true)) {
            return blockingReadAction {
                val hierarchy = linkedSetOf(element).apply { addAll(hierarchyMembers) }
                hierarchy.flatMap { member ->
                    val scope = kotlinLexicalScope(member)
                    PsiTreeUtil.collectElementsOfType(scope, PsiNamedElement::class.java)
                        .filter {
                            it !in hierarchy &&
                                it.language.id.equals("kotlin", ignoreCase = true) &&
                                it.name == newName &&
                                kotlinLexicalScope(it) === scope
                        }
                }
                    .map { "another declaration named $newName already exists in this scope" }
                    .distinct()
            }
        }
        if (element.language.id.equals("Python", ignoreCase = true)) {
            return blockingReadAction {
                val hierarchy = linkedSetOf(element).apply { addAll(hierarchyMembers) }
                hierarchy.flatMap { member ->
                    val scope = pythonLexicalScope(member)
                    PsiTreeUtil.collectElementsOfType(scope, PsiNamedElement::class.java)
                        .filter {
                            it !in hierarchy &&
                                it.language.id.equals("Python", ignoreCase = true) &&
                                it.name == newName &&
                                pythonLexicalScope(it) === scope
                        }
                }
                    .map { "another declaration named $newName already exists in this scope" }
                    .distinct()
            }
        }
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        val allRenames = linkedMapOf<com.intellij.psi.PsiElement, String>(element to newName)
        val prepare = {
            RenamePsiElementProcessor.forElement(element)
                .prepareRenaming(element, newName, allRenames, scope)
        }
        blockingReadAction { prepare() }
        return blockingReadAction {
            val conflicts = MultiMap<com.intellij.psi.PsiElement, String>()
            allRenames.forEach { (target, targetName) ->
                val usages = RenameUtil.findUsages(
                    target,
                    targetName,
                    scope,
                    false,
                    false,
                    allRenames,
                )
                RenameUtil.addConflictDescriptions(usages, conflicts)
                RenamePsiElementProcessor.forElement(target)
                    .findExistingNameConflicts(target, targetName, conflicts, allRenames)
            }
            conflicts.values().distinct().sorted()
        }
    }

    private fun pythonLexicalScope(element: com.intellij.psi.PsiElement): com.intellij.psi.PsiElement =
        generateSequence(element.parent) { it.parent }
            .firstOrNull {
                it is com.intellij.psi.PsiFile ||
                    it.javaClass.simpleName.contains("Function") ||
                    it.javaClass.simpleName.contains("Class")
            }
            ?: element.containingFile

    private fun kotlinLexicalScope(element: com.intellij.psi.PsiElement): com.intellij.psi.PsiElement =
        generateSequence(element.parent) { it.parent }
            .firstOrNull {
                it is KtBlockExpression ||
                    it is KtNamedFunction ||
                    it is KtClassOrObject ||
                    it is KtFile
            }
            ?: element.containingFile

    private fun checkWritableAndClean(files: Collection<VirtualFile>) {
        val documentManager = FileDocumentManager.getInstance()
        files.forEach { file ->
            if (!file.isWritable) {
                throw RefactorException("READ_ONLY_FILE", 4, "${file.path} is read-only")
            }
            val document = documentManager.getCachedDocument(file)
            if (document != null && documentManager.isDocumentUnsaved(document)) {
                throw RefactorException(
                    "DIRTY_AFFECTED_DOCUMENT",
                    4,
                    "${file.path} has unsaved IDE changes",
                )
            }
        }
    }

    private fun elapsedMs(started: Long): Long =
        (System.nanoTime() - started) / 1_000_000

    private fun reviewReport(
        project: Project,
        symbol: ResolvedSymbol,
        newName: String,
        affected: Collection<VirtualFile>,
        analysis: UsageAnalysis,
        conflicts: List<String>,
        warning: String,
    ): JsonObject = buildJsonObject {
        put("status", "NEEDS_REVIEW")
        put("target", symbol.target)
        put("newName", newName)
        put("usageSummary", analysis.result["summary"] ?: JsonObject(emptyMap()))
        put(
            "affectedFiles",
            buildJsonObject {
                put(
                    "source",
                    buildJsonArray {
                        affected.filter { it.extension?.lowercase() in sourceExtensions }
                            .map { relative(project, it.path, it) }
                            .distinct()
                            .sorted()
                            .forEach(::add)
                    },
                )
                put(
                    "nonSource",
                    buildJsonArray {
                        affected.filter { it.extension?.lowercase() !in sourceExtensions }
                            .map { relative(project, it.path, it) }
                            .distinct()
                            .sorted()
                            .forEach(::add)
                    },
                )
            },
        )
        put("conflicts", buildJsonArray { conflicts.forEach(::add) })
        put(
            "warnings",
            buildJsonArray {
                add(warning)
                if (symbol.element.language.id.equals("Python", ignoreCase = true)) {
                    add(PYTHON_WARNING)
                }
            },
        )
    }

    private fun changedRegions(
        before: ByteArray,
        after: ByteArray,
        charset: Charset,
    ): List<IntRange> {
        val oldLines = before.toString(charset).lines()
        val newLines = after.toString(charset).lines()
        val size = maxOf(oldLines.size, newLines.size)
        val changedLines = (0 until size)
            .filter { oldLines.getOrNull(it) != newLines.getOrNull(it) }
            .map { it + 1 }
        if (changedLines.isEmpty()) return emptyList()
        return buildList {
            var start = changedLines.first()
            var end = start
            changedLines.drop(1).forEach { line ->
                if (line == end + 1) {
                    end = line
                } else {
                    add(start..end)
                    start = line
                    end = line
                }
            }
            add(start..end)
        }
    }

    private fun buildDiffResult(
        project: Project,
        mode: String,
        changed: List<FileChange>,
    ): JsonElement {
        if (mode == "none") return JsonNull
        val patch = buildString {
            changed.forEach { change ->
                val oldRelative = change.oldPath?.let {
                    oldRelative(project, change.file, it, change.newPath)
                }
                val newRelative = change.newPath?.let {
                    relative(project, it, change.file)
                }
                val left = oldRelative ?: newRelative.orEmpty()
                val right = newRelative ?: oldRelative.orEmpty()
                val oldText = change.before?.toString(change.charset).orEmpty()
                val newText = change.after?.toString(change.charset).orEmpty()
                append("diff --git a/$left b/$right\n")
                if (oldRelative != null && newRelative != null && oldRelative != newRelative) {
                    append("rename from $oldRelative\n")
                    append("rename to $newRelative\n")
                }
                if (oldRelative == null) append("new file mode 100644\n")
                if (newRelative == null) append("deleted file mode 100644\n")
                append(if (oldRelative == null) "--- /dev/null\n" else "--- a/$oldRelative\n")
                append(if (newRelative == null) "+++ /dev/null\n" else "+++ b/$newRelative\n")
                val oldLines = patchLines(oldText)
                val newLines = patchLines(newText)
                val oldStart = if (oldLines.isEmpty()) 0 else 1
                val newStart = if (newLines.isEmpty()) 0 else 1
                append("@@ -$oldStart,${oldLines.size} +$newStart,${newLines.size} @@\n")
                appendPatchLines('-', oldText, oldLines)
                appendPatchLines('+', newText, newLines)
            }
        }
        return when (mode) {
            "inline" -> {
                val bytes = patch.toByteArray(Charsets.UTF_8)
                val truncated = bytes.size > MAX_INLINE_DIFF_BYTES
                val content = if (truncated) {
                    var end = MAX_INLINE_DIFF_BYTES
                    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
                    bytes.copyOf(end).toString(Charsets.UTF_8)
                } else {
                    patch
                }
                buildJsonObject {
                    put("mode", "inline")
                    put("content", content)
                    put("truncated", truncated)
                    put("totalBytes", bytes.size)
                }
            }
            "file" -> {
                val path = Files.createTempFile("refactor-", ".diff")
                Files.writeString(path, patch)
                buildJsonObject {
                    put("mode", "file")
                    put("path", path.toAbsolutePath().toString())
                    put("bytes", patch.toByteArray(Charsets.UTF_8).size)
                }
            }
            else -> throw RefactorException("INTERNAL_ERROR", 5, "unknown diff mode: $mode")
        }
    }

    private fun patchLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    private fun StringBuilder.appendPatchLines(
        prefix: Char,
        text: String,
        lines: List<String>,
    ) {
        lines.forEachIndexed { index, line ->
            append(prefix).append(line).append('\n')
            if (index == lines.lastIndex && !text.endsWith('\n')) {
                append("\\ No newline at end of file\n")
            }
        }
    }

    private fun relative(project: Project, path: String, file: VirtualFile? = null): String {
        val nioRelative = runCatching {
            java.nio.file.Path.of(project.basePath.orEmpty())
                .relativize(java.nio.file.Path.of(path))
                .toString()
                .replace('\\', '/')
        }.getOrNull()
        if (
            nioRelative != null &&
            !nioRelative.startsWith("../") &&
            !nioRelative.startsWith('/')
        ) {
            return nioRelative
        }
        if (file != null) {
            val contentRelative = blockingReadAction {
                val root = com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                    .fileIndex
                    .getContentRootForFile(file)
                root?.let {
                    com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it, '/')
                }
            }
            if (contentRelative != null) return contentRelative
        }
        val normalizedPath = path.replace('\\', '/')
        val rootRelative = blockingReadAction {
            com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                .contentRoots
                .asSequence()
                .map { it.path.replace('\\', '/').trimEnd('/') }
                .mapNotNull { root ->
                    when {
                        normalizedPath.equals(root, ignoreCase = isWindows) -> ""
                        normalizedPath.startsWith("$root/", ignoreCase = isWindows) ->
                            normalizedPath.substring(root.length + 1)
                        else -> null
                    }
                }
                .firstOrNull()
        }
        if (rootRelative != null) return rootRelative
        return nioRelative ?: path
    }

    private fun oldRelative(
        project: Project,
        file: VirtualFile?,
        oldPath: String,
        newPath: String?,
    ): String {
        val old = relative(project, oldPath)
        if (!old.startsWith("../") && !old.startsWith('/')) return old
        val currentPath = newPath ?: file?.path ?: return old
        val current = java.nio.file.Path.of(relative(project, currentPath, file))
        return (current.parent?.resolve(java.nio.file.Path.of(oldPath).fileName)
            ?: java.nio.file.Path.of(oldPath).fileName)
            .toString()
            .replace('\\', '/')
    }

    private companion object {
        val sourceExtensions = setOf("java", "kt", "kts", "py")
        val diffModes = setOf("none", "inline", "file")
        val locks = ConcurrentHashMap<Project, ReentrantLock>()
        const val MAX_INLINE_DIFF_BYTES = 64 * 1024
        const val PYTHON_WARNING = "Dynamic Python references cannot be discovered reliably."
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private class NonInteractiveRenameProcessor(
        project: Project,
        element: PsiNamedElement,
        newName: String,
    ) : RenameProcessor(project, element, newName, false, false) {
        init {
            setPreviewUsages(false)
        }

        override fun showConflicts(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): Boolean {
            throw RefactorException(
                "NEEDS_REVIEW",
                2,
                "rename has conflicts and was not applied",
                buildJsonObject {
                    put("status", "NEEDS_REVIEW")
                    put(
                        "conflicts",
                        buildJsonArray {
                            conflicts.values().distinct().sorted().forEach(::add)
                        },
                    )
                },
            )
        }

        override fun showAutomaticRenamingDialog(renamer: AutomaticRenamer): Boolean = false

        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false
    }
}
