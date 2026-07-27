package io.github.glinte.refactor.operations

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * The boundary between transport code and IntelliJ PSI/refactoring implementations.
 */
class RefactorOperationRouter {
    private val workspace = WorkspaceService()
    private val resolver = SymbolResolver()
    private val usages = UsageService(resolver)
    private val rename = RenameService(resolver, usages)

    fun route(method: String, params: JsonObject): JsonElement {
        if (method == "status") return status()
        val project = workspace.project(params)
        val lock = projectLocks.computeIfAbsent(project) { ReentrantReadWriteLock() }
        return when (method) {
            "status" -> status()
            "sync" -> lock.read { workspace.sync(project, params) }
            "resolve" -> lock.read {
                workspace.sync(project, params)
                smartRead(project) { resolver.resolve(project, params).target }
            }
            "usages" -> lock.read {
                workspace.sync(project, params)
                usagesLock.withLock {
                    smartRead(project) {
                        val symbol = resolver.resolve(project, params)
                        val max = params["max"]?.jsonPrimitive?.intOrNull ?: 200
                        if (max !in 1..10_000) {
                            throw RefactorException(
                                "INVALID_ARGUMENT",
                                3,
                                "max must be between 1 and 10000",
                            )
                        }
                        usages.analyze(project, symbol, max).result
                    }
                }
            }
            "rename" -> lock.write {
                // Sync and resolve while holding the mutation lock so analysis cannot go stale
                // before the refactoring processor starts.
                val sync = workspace.sync(project, params)
                val symbol = smartRead(project) { resolver.resolve(project, params) }
                rename.rename(
                    project,
                    params,
                    symbol,
                    sync["syncMs"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }
            else -> throw RefactorException("INTERNAL_ERROR", 5, "unknown method: $method")
        }
    }

    private fun status(): JsonObject {
        val projects = ProjectManager.getInstance().openProjects
            .filterNot(Project::isDisposed)
            .sortedBy { it.basePath }

        return buildJsonObject {
            put("protocolVersion", 1)
            put(
                "watcherOperational",
                (LocalFileSystem.getInstance() as? LocalFileSystemImpl)
                    ?.fileWatcher
                    ?.isOperational
                    ?: false,
            )
            put(
                "languagePlugins",
                buildJsonObject {
                    put("java", pluginInstalled("com.intellij.java"))
                    put("kotlin", pluginInstalled("org.jetbrains.kotlin"))
                    put("kotlinMode", "K2")
                    put("python", pluginInstalled("PythonCore") || pluginInstalled("Pythonid"))
                },
            )
            put(
                "projects",
                buildJsonArray {
                    projects.forEach { project ->
                        add(
                            buildJsonObject {
                                put("name", project.name)
                                put("root", project.basePath ?: "")
                                put("indexing", DumbService.isDumb(project))
                                put("ready", !DumbService.isDumb(project))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun pluginInstalled(id: String): Boolean =
        PluginManagerCore.isPluginInstalled(PluginId.getId(id))

    private fun <T> smartRead(project: Project, action: () -> T): T =
        ReadAction.nonBlocking(Callable(action))
            .inSmartMode(project)
            .executeSynchronously()

    private companion object {
        val projectLocks = ConcurrentHashMap<Project, ReentrantReadWriteLock>()
        val usagesLock = java.util.concurrent.locks.ReentrantLock()
    }
}
