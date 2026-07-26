package io.github.glinte.refactor.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.glinte.refactor.operations.RefactorOperationRouter
import io.github.glinte.refactor.protocol.RefactorException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

private const val MAX_REQUEST_BYTES = 1024 * 1024

@Service(Service.Level.APP)
class RefactorAgentServer : Disposable {
    private val log = logger<RefactorAgentServer>()
    private val json = Json { ignoreUnknownKeys = true }
    private val router = RefactorOperationRouter()
    private val token = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val descriptorStore = DescriptorStore(server.address.port, token)

    init {
        server.createContext("/rpc", ::handle)
        server.executor = AppExecutorUtil.getAppExecutorService()
        server.start()
        log.info("refactor agent listening on 127.0.0.1:${server.address.port}")
    }

    fun register(project: Project) {
        descriptorStore.write(ProjectManager.getInstance().openProjects.toList() + project)
    }

    override fun dispose() {
        descriptorStore.close()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!exchange.remoteAddress.address.isLoopbackAddress) {
                sendEmpty(exchange, 403)
                return
            }
            if (exchange.requestMethod != "POST") {
                sendEmpty(exchange, 405)
                return
            }
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                sendEmpty(exchange, 401)
                return
            }

            val bytes = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
            if (bytes.size > MAX_REQUEST_BYTES) {
                sendEmpty(exchange, 413)
                return
            }

            val request = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
            val id = request["id"] ?: JsonNull
            val method = request["method"]?.jsonPrimitive?.content
                ?: throw SerializationException("missing method")
            val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
            val result = router.route(method, params)
            sendJson(exchange, success(id, result))
        } catch (error: RefactorException) {
            sendJson(exchange, failure(JsonNull, error))
        } catch (error: Exception) {
            log.warn("Failed to handle refactor request", error)
            sendJson(
                exchange,
                failure(
                    JsonNull,
                    RefactorException("INTERNAL_ERROR", 5, error.message ?: "internal plugin error"),
                ),
            )
        } finally {
            exchange.close()
        }
    }

    private fun success(id: JsonElement, result: JsonElement): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun failure(id: JsonElement, error: RefactorException): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", -32000)
                    put("message", error.message)
                    put(
                        "data",
                        buildJsonObject {
                            put("code", error.symbolicCode)
                            put("exitCode", error.exitCode)
                        },
                    )
                },
            )
        }

    private fun sendJson(exchange: HttpExchange, body: JsonElement) {
        val bytes = Json.encodeToString(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
    }
}
