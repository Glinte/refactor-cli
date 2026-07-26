package io.github.glinte.refactor.operations

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The boundary between transport code and IntelliJ PSI/refactoring implementations.
 *
 * Status is deliberately functional in the skeleton. The remaining handlers fail loudly
 * until their PSI-backed implementations are added.
 */
class RefactorOperationRouter {
    fun route(method: String, params: JsonObject): JsonElement =
        when (method) {
            "status" -> status()
            "sync" -> notImplemented("sync")
            "resolve" -> notImplemented("resolve")
            "usages" -> notImplemented("usages")
            "rename" -> notImplemented("rename")
            else -> throw RefactorException("INTERNAL_ERROR", 5, "unknown method: $method")
        }

    private fun status(): JsonObject {
        val projects = ProjectManager.getInstance().openProjects
            .filterNot(Project::isDisposed)
            .sortedBy { it.basePath }

        return buildJsonObject {
            put("protocolVersion", 1)
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

    private fun notImplemented(operation: String): Nothing =
        throw RefactorException(
            symbolicCode = "INTERNAL_ERROR",
            exitCode = 5,
            message = "$operation is present in the protocol skeleton but its IntelliJ implementation is not built yet",
        )
}
