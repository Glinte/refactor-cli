package io.github.glinte.refactor.operations

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal data class UsageRecord(
    val file: VirtualFile,
    val path: String,
    val line: Int,
    val col: Int,
    val kind: String,
    val excerpt: String,
)

internal data class UsageAnalysis(
    val records: List<UsageRecord>,
    val result: JsonObject,
) {
    val nonSourceFiles: Set<VirtualFile>
        get() = records.filter { it.kind == "NON_SOURCE" }.mapTo(linkedSetOf(), UsageRecord::file)
}

internal class UsageService(
    private val resolver: SymbolResolver,
) {
    fun analyze(
        project: Project,
        symbol: ResolvedSymbol,
        max: Int,
    ): UsageAnalysis = analyze(project, listOf(symbol), max)

    fun analyze(
        project: Project,
        symbols: Collection<ResolvedSymbol>,
        max: Int,
    ): UsageAnalysis {
        require(symbols.isNotEmpty())
        val primary = symbols.first()
        val records = buildList {
            symbols.forEach { symbol ->
                ReferencesSearch.search(
                    symbol.element,
                    GlobalSearchScope.projectScope(project),
                ).findAll().mapNotNullTo(this) { reference ->
                    record(project, reference)
                }
                (symbol.element as? PsiMethod)?.let { method ->
                    OverridingMethodsSearch.search(method).findAll().mapNotNullTo(this) { override ->
                        val identifier = override.nameIdentifier ?: override
                        record(project, identifier, identifier.textRange.startOffset, "OVERRIDE")
                    }
                }
            }
            symbols.drop(1).mapNotNullTo(this) { symbol ->
                val declaration = (symbol.element as? PsiNameIdentifierOwner)
                    ?.nameIdentifier
                    ?: symbol.element
                record(
                    project,
                    declaration,
                    declaration.textRange.startOffset,
                    "OVERRIDE",
                )
            }
        }.distinctBy { listOf(it.path, it.line, it.col, it.kind) }
            .sortedWith(compareBy(UsageRecord::path, UsageRecord::line, UsageRecord::col))
        val shown = records.take(max)
        val counts = records.groupingBy(UsageRecord::kind).eachCount()

        val result = buildJsonObject {
            put("target", primary.target)
            put("totalUsages", records.size)
            put("truncated", records.size > shown.size)
            put(
                "summary",
                buildJsonObject {
                    put("code", counts["CODE"] ?: 0)
                    put("imports", counts["IMPORT"] ?: 0)
                    put("nonSource", counts["NON_SOURCE"] ?: 0)
                    put("nonCode", counts["NON_CODE"] ?: 0)
                    put("overrides", counts["OVERRIDE"] ?: 0)
                },
            )
            put(
                "usages",
                buildJsonArray {
                    shown.forEach { usage ->
                        add(
                            buildJsonObject {
                                put("path", usage.path)
                                put("line", usage.line)
                                put("col", usage.col)
                                put("kind", usage.kind)
                                put("excerpt", usage.excerpt)
                            },
                        )
                    }
                },
            )
            put(
                "warnings",
                buildJsonArray {
                    if (primary.element.language.id.equals("Python", ignoreCase = true)) {
                        add("Dynamic Python references cannot be discovered reliably.")
                    }
                },
            )
        }
        return UsageAnalysis(records, result)
    }

    private fun record(project: Project, reference: PsiReference): UsageRecord? {
        val element = reference.element
        val offset = element.textRange.startOffset + reference.rangeInElement.startOffset
        return record(project, element, offset, null)
    }

    private fun record(
        project: Project,
        element: PsiElement,
        offset: Int,
        explicitKind: String?,
    ): UsageRecord? {
        val psiFile = element.containingFile ?: return null
        val file = psiFile.virtualFile ?: return null
        val document = psiFile.viewProvider.document ?: return null
        if (offset !in 0..document.textLength) return null
        val lineIndex = document.getLineNumber(offset.coerceAtMost(document.textLength))
        val lineStart = document.getLineStartOffset(lineIndex)
        val kind = explicitKind ?: classify(element, file)
        return UsageRecord(
            file = file,
            path = relativePath(project, file),
            line = lineIndex + 1,
            col = offset - lineStart + 1,
            kind = kind,
            excerpt = excerpt(document, lineIndex),
        )
    }

    private fun classify(element: com.intellij.psi.PsiElement, file: VirtualFile): String {
        if (PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null) {
            return "IMPORT"
        }
        val extension = file.extension?.lowercase()
        return when (extension) {
            "java", "kt", "kts", "py" -> "CODE"
            "xml", "properties", "yaml", "yml", "json", "toml" -> "NON_SOURCE"
            else -> "NON_CODE"
        }
    }

    private fun excerpt(document: Document, line: Int): String {
        val text = document.charsSequence
            .subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
            .toString()
            .trim()
        return if (text.length <= 240) text else "${text.take(237)}..."
    }

    private fun relativePath(project: Project, file: VirtualFile): String =
        runCatching {
            Path.of(project.basePath.orEmpty())
                .relativize(Path.of(file.path))
                .toString()
                .replace('\\', '/')
        }.getOrNull()
            ?.takeIf { !it.startsWith("../") && !it.startsWith('/') }
            ?: com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                .fileIndex
                .getContentRootForFile(file)
                ?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it, '/') }
            ?: file.path
}
