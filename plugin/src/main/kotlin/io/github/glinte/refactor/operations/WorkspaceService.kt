package io.github.glinte.refactor.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.psi.PsiDocumentManager
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WorkspaceService(
    private val smartModeTimeoutMs: Long = 30_000,
) {
    fun project(params: JsonObject): Project {
        val requested = params["project"]?.jsonPrimitive?.content
            ?: throw RefactorException("PROJECT_NOT_FOUND", 4, "request is missing project")
        val path = runCatching { Path.of(requested).toAbsolutePath().normalize() }
            .getOrElse {
                throw RefactorException("PROJECT_NOT_FOUND", 4, "invalid project path: $requested")
            }
        return ProjectManager.getInstance().openProjects
            .filterNot(Project::isDisposed)
            .singleOrNull {
                it.basePath?.let { root ->
                    pathsEqual(Path.of(root).toAbsolutePath().normalize(), path)
                } == true
            }
            ?: throw RefactorException(
                "PROJECT_NOT_FOUND",
                4,
                "the IntelliJ instance does not have $path open",
            )
    }

    fun sync(project: Project, params: JsonObject): JsonObject =
        syncLocks.computeIfAbsent(project) { ReentrantLock() }.withLock {
            syncLocked(project, params)
        }

    private fun syncLocked(project: Project, params: JsonObject): JsonObject {
        val started = System.nanoTime()
        val touched = params["touched"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        detectExternalDirtyConflicts(project)
        val application = ApplicationManager.getApplication()
        val refreshed = if (application.isUnitTestMode && application.isDispatchThread) {
            touched
        } else if (touched.isEmpty()) {
            val roots = contentRoots(project)
            refresh(roots, recursively = true)
            roots.map(VirtualFile::getPath)
        } else {
            val hinted = refreshTouched(project, touched)
            val roots = contentRoots(project)
            refresh(roots, recursively = true)
            (hinted + roots.map(VirtualFile::getPath)).distinct()
        }

        val commit = {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        if (application.isDispatchThread) commit() else application.invokeAndWait(commit)
        val dumbService = DumbService.getInstance(project)
        val smart = if (dumbService.isDumb) {
            val ready = CountDownLatch(1)
            dumbService.runWhenSmart(ready::countDown)
            ready.await(smartModeTimeoutMs, TimeUnit.MILLISECONDS)
        } else {
            true
        }
        if (!smart) {
            throw RefactorException(
                "SYNC_TIMEOUT",
                4,
                "indexing did not finish within $smartModeTimeoutMs ms",
                buildJsonObject {
                    put("elapsedMs", elapsedMs(started))
                    put("timeoutMs", smartModeTimeoutMs)
                },
            )
        }

        return buildJsonObject {
            put("status", "SYNCED")
            put("refreshedPaths", refreshed.size)
            put("syncMs", elapsedMs(started))
            put("indexing", false)
        }
    }

    private fun refreshTouched(project: Project, touched: List<String>): List<String> {
        val root = Path.of(project.basePath ?: error("project has no base path"))
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
            .map { Path.of(it.path).toAbsolutePath().normalize() }
        val paths = touched.flatMap { value ->
            val path = runCatching {
                Path.of(value).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
            }.getOrElse {
                throw RefactorException("FILE_NOT_FOUND", 3, "invalid touched path: $value")
            }
            if (contentRoots.none { pathIsWithin(it, path) }) {
                throw RefactorException(
                    "FILE_NOT_FOUND",
                    3,
                    "touched path $value is outside project content",
                )
            }
            listOfNotNull(
                path,
                path.parent?.takeIf { parent -> contentRoots.any { pathIsWithin(it, parent) } },
            )
        }.distinct()
        val localFileSystem = LocalFileSystem.getInstance()
        val files = paths.mapNotNull { path ->
            localFileSystem.refreshAndFindFileByNioFile(path)
                ?: path.parent?.let(localFileSystem::findFileByNioFile)
        }.distinct()
        refresh(files, recursively = false)
        return paths.map(Path::toString)
    }

    private fun contentRoots(project: Project): MutableList<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toMutableList()
        if (roots.isEmpty()) {
            project.basePath
                ?.let(Path::of)
                ?.let(LocalFileSystem.getInstance()::refreshAndFindFileByNioFile)
                ?.let(roots::add)
        }
        return roots
    }

    private fun detectExternalDirtyConflicts(project: Project) {
        val roots = ProjectRootManager.getInstance(project).contentRoots
            .map { Path.of(it.path).toAbsolutePath().normalize() }
        val conflicts = FileDocumentManager.getInstance().unsavedDocuments.mapNotNull { document ->
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@mapNotNull null
            if (file.fileSystem.protocol != "file") return@mapNotNull null
            val path = runCatching { Path.of(file.path).toAbsolutePath().normalize() }
                .getOrNull()
                ?: return@mapNotNull null
            if (roots.none { pathIsWithin(it, path) }) return@mapNotNull null
            val diskChanged = !Files.exists(path) || runCatching {
                Files.size(path) != file.length ||
                    Files.getLastModifiedTime(path).toMillis() != file.timeStamp
            }.getOrDefault(true)
            file.path.takeIf { diskChanged }
        }
        if (conflicts.isNotEmpty()) {
            throw RefactorException(
                "EXTERNAL_CHANGE_CONFLICT",
                4,
                "files have both unsaved IDE edits and external disk changes",
                buildJsonObject {
                    put(
                        "files",
                        kotlinx.serialization.json.buildJsonArray {
                            conflicts.sorted().forEach(::add)
                        },
                    )
                },
            )
        }
    }

    private fun refresh(files: Collection<VirtualFile>, recursively: Boolean) {
        if (files.isEmpty()) return
        RefreshQueue.getInstance().refresh(false, recursively, null, files)
    }

    private fun pathsEqual(left: Path, right: Path): Boolean =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            left.toString().equals(right.toString(), ignoreCase = true)
        } else {
            left == right
        }

    private fun pathIsWithin(root: Path, candidate: Path): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return candidate.startsWith(root)
        }
        val rootText = root.toString().trimEnd('\\', '/')
        val candidateText = candidate.toString()
        return candidateText.equals(rootText, ignoreCase = true) ||
            candidateText.startsWith("$rootText\\", ignoreCase = true) ||
            candidateText.startsWith("$rootText/", ignoreCase = true)
    }

    private fun elapsedMs(started: Long): Long =
        (System.nanoTime() - started) / 1_000_000

    private companion object {
        val syncLocks = ConcurrentHashMap<Project, ReentrantLock>()
    }
}
