package com.sa.computebridge.server

import com.sa.computebridge.ResourceGuard
import com.sa.computebridge.ResourceLimitStore
import com.sa.computebridge.engine.BrainEngine
import com.sa.computebridge.engine.EngineState
import com.sa.computebridge.engine.ModelFileManager
import com.sa.computebridge.network.NetworkInfo
import com.sa.computebridge.network.PairingStore
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WorkerHttpServer(
    port: Int,
    private val pairing: PairingStore,
    private val models: ModelFileManager,
    private val onRequest: () -> Unit
) : NanoHTTPD("0.0.0.0", port) {
    private val serverPort = port
    private val context = pairing.context
    private val limits = ResourceLimitStore(context)
    private val guard = ResourceGuard(context, limits)
    private val pairFailures = AtomicInteger(0)
    private val pairWindowStart = AtomicLong(0L)
    private val pairBlockedUntil = AtomicLong(0L)

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/v1/health" -> health()
            session.method == Method.GET && session.uri == "/v1/worker" -> workerInfo(session)
            session.method == Method.POST && session.uri == "/v1/pair" -> pair(session)
            session.method == Method.GET && session.uri == "/v1/models" -> models(session)
            session.method == Method.GET && session.uri == "/v1/settings/resource" -> resourceSettings(session)
            session.method == Method.POST && session.uri == "/v1/settings/resource" -> updateResourceSettings(session)
            session.method == Method.POST && session.uri == "/v1/chat/completions" -> chat(session)
            else -> error(Response.Status.NOT_FOUND, "not_found", "No route: ${session.method} ${session.uri}")
        }
    } catch (e: Exception) {
        error(Response.Status.INTERNAL_ERROR, "server_error", e.message ?: "Internal server error")
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"] ?: return false
        return auth == "Bearer ${pairing.pairingToken}"
    }

    private fun health(): Response = json(
        Response.Status.OK,
        JSONObject().put("status", "ready")
    )

    private fun workerInfo(session: IHTTPSession): Response {
        if (!authorized(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized", "Pairing required")
        return json(
            Response.Status.OK,
            JSONObject()
                .put("worker_id", pairing.workerId)
                .put("addresses", JSONArray(NetworkInfo.ipv4Addresses()))
                .put("port", serverPort)
                .put("engine", engineJson())
                .put("models", modelArray())
                .put("resource", resourceJson())
        )
    }

    private fun pair(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (now < pairBlockedUntil.get()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "pairing_locked", "Too many failed pairing attempts; try again later")
        }
        if (now - pairWindowStart.get() > 60_000L) {
            pairWindowStart.set(now)
            pairFailures.set(0)
        }
        val body = runCatching { readBody(session) }.getOrElse {
            return error(Response.Status.BAD_REQUEST, "invalid_request", "Could not read pairing request")
        }
        val supplied = runCatching { JSONObject(body).optString("pairing_token") }.getOrNull() ?: ""
        if (supplied != pairing.pairingToken) {
            val failures = pairFailures.incrementAndGet()
            if (failures >= 5) {
                pairBlockedUntil.set(now + 30_000L)
            }
            return error(Response.Status.UNAUTHORIZED, "invalid_pairing", "Pairing token rejected")
        }
        pairFailures.set(0)
        onRequest()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("paired", true)
                .put("worker_id", pairing.workerId)
                .put("access_token", pairing.pairingToken)
        )
    }

    private fun models(session: IHTTPSession): Response {
        if (!authorized(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized", "Pairing required")
        onRequest()
        return json(Response.Status.OK, JSONObject().put("object", "list").put("data", modelArray()))
    }

    private fun resourceSettings(session: IHTTPSession): Response {
        if (!authorized(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized", "Pairing required")
        return json(Response.Status.OK, resourceJson())
    }

    private fun updateResourceSettings(session: IHTTPSession): Response {
        if (!authorized(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized", "Pairing required")
        val request = runCatching { JSONObject(readBody(session)) }
            .getOrElse { return error(Response.Status.BAD_REQUEST, "invalid_request", "Invalid JSON") }
        if (!request.has("percent")) {
            return error(Response.Status.BAD_REQUEST, "invalid_request", "percent is required")
        }
        val percent = request.optInt("percent", -1)
        if (percent !in ResourceLimitStore.MIN_PERCENT..ResourceLimitStore.MAX_PERCENT) {
            return error(
                Response.Status.BAD_REQUEST,
                "invalid_limit",
                "percent must be between ${ResourceLimitStore.MIN_PERCENT} and ${ResourceLimitStore.MAX_PERCENT}"
            )
        }
        limits.percent = percent
        onRequest()
        return json(Response.Status.OK, resourceJson().put("requires_model_reload", BrainEngine.isLoaded))
    }

    private fun chat(session: IHTTPSession): Response {
        if (!authorized(session)) return error(Response.Status.UNAUTHORIZED, "unauthorized", "Pairing required")
        val request = runCatching { JSONObject(readBody(session)) }
            .getOrElse { return error(Response.Status.BAD_REQUEST, "invalid_request", "Invalid JSON") }
        val messages = request.optJSONArray("messages")
            ?: return error(Response.Status.BAD_REQUEST, "invalid_request", "messages is required")
        if (messages.length() == 0 || messages.length() > 64) {
            return error(Response.Status.BAD_REQUEST, "invalid_request", "messages must contain 1-64 items")
        }
        if (!BrainEngine.isLoaded) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "model_not_loaded", "No GGUF model is loaded on Worker")
        }
        if (!guard.canStartTask()) {
            return error(Response.Status.SERVICE_UNAVAILABLE, "resource_limit", "Worker resource limit reached")
        }

        val prompt = runCatching { buildPrompt(messages) }.getOrElse {
            return error(Response.Status.BAD_REQUEST, "invalid_request", it.message ?: "Invalid messages")
        }
        val maxPromptChars = limits.maxContextSize() * 6
        if (prompt.length > maxPromptChars) {
            return error(Response.Status.BAD_REQUEST, "context_too_large", "Prompt exceeds the current worker context budget")
        }
        val requestedMaxTokens = request.optInt("max_tokens", 512).coerceIn(1, 4096)
        val maxTokens = limits.maxTokens(requestedMaxTokens)
        val temperature = request.optDouble("temperature", 0.7).toFloat().coerceIn(0.05f, 2.0f)
        val topP = request.optDouble("top_p", 0.9).toFloat().coerceIn(0.05f, 1.0f)
        val stream = request.optBoolean("stream", true)
        onRequest()
        val modelName = (BrainEngine.state.value as? EngineState.Loaded)?.modelName ?: "worker-local"

        return if (stream) {
            streamChat(prompt, maxTokens, temperature, topP, modelName)
        } else {
            val result = runBlocking {
                runCatching {
                    buildString {
                        BrainEngine.generate(context, prompt, maxTokens, temperature, topP).collect { append(it) }
                    }
                }
            }
            result.fold(
                onSuccess = { text ->
                    json(
                        Response.Status.OK,
                        JSONObject()
                            .put("id", "cb-${System.currentTimeMillis()}")
                            .put("object", "chat.completion")
                            .put("model", modelName)
                            .put("choices", JSONArray().put(JSONObject()
                                .put("index", 0)
                                .put("message", JSONObject().put("role", "assistant").put("content", text))
                                .put("finish_reason", "stop")))
                    )
                },
                onFailure = { e -> error(Response.Status.SERVICE_UNAVAILABLE, "generation_failed", e.message ?: "Generation failed") }
            )
        }
    }

    private fun streamChat(prompt: String, maxTokens: Int, temperature: Float, topP: Float, modelName: String): Response {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 8192)
        Thread {
            try {
                runBlocking {
                    BrainEngine.generate(context, prompt, maxTokens, temperature, topP).collect { token ->
                        val chunk = JSONObject()
                            .put("id", "cb-${System.currentTimeMillis()}")
                            .put("object", "chat.completion.chunk")
                            .put("model", modelName)
                            .put("choices", JSONArray().put(JSONObject()
                                .put("index", 0)
                                .put("delta", JSONObject().put("content", token))
                                .put("finish_reason", JSONObject.NULL)))
                        out.write("data: $chunk\n\n".toByteArray())
                        out.flush()
                    }
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
                // Broken pipe/client disconnect is a normal network failure,
                // not an app-crash condition. Ask the native engine to stop
                // at the next safe callback/checkpoint.
                BrainEngine.cancelGeneration()
                val errorChunk = JSONObject()
                    .put("error", JSONObject().put("type", "generation_failed").put("message", e.message ?: "Generation failed"))
                runCatching {
                    out.write("data: $errorChunk\n\n".toByteArray())
                    out.flush()
                }
            } finally {
                runCatching { out.close() }
            }
        }.apply { isDaemon = true }.start()
        return newChunkedResponse(Response.Status.OK, "text/event-stream", input).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    private fun resourceJson(): JSONObject = JSONObject()
        .put("percent", limits.percent)
        .put("min_percent", ResourceLimitStore.MIN_PERCENT)
        .put("max_percent", ResourceLimitStore.MAX_PERCENT)
        .put("max_cpu_threads", limits.maxCpuThreads())
        .put("max_tokens", limits.maxTokens(4096))
        .put("max_context", limits.maxContextSize())
        .put("memory_budget_bytes", limits.memoryBudgetBytes(context))
        .put("memory_status", JSONObject().put("within_budget", guard.status().withinBudget))

    private fun modelArray(): JSONArray {
        val arr = JSONArray()
        models.listModels().forEach { model ->
            arr.put(JSONObject().put("id", model.name).put("object", "model").put("bytes", model.sizeBytes))
        }
        return arr
    }

    private fun engineJson(): JSONObject = when (val state = BrainEngine.state.value) {
        EngineState.Unloaded -> JSONObject().put("state", "unloaded")
        is EngineState.Loading -> JSONObject().put("state", "loading").put("model", state.modelName)
        is EngineState.Loaded -> JSONObject().put("state", "loaded").put("model", state.modelName).put("context_size", state.contextSize)
        is EngineState.Error -> JSONObject().put("state", "error").put("message", state.message)
    }

    private fun buildPrompt(messages: JSONArray): String = buildString {
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i)
                ?: throw IllegalArgumentException("messages[$i] must be an object")
            val role = msg.optString("role", "user").take(32)
            val content = msg.optString("content", "")
            append("<|im_start|>").append(role).append('\n')
            append(content).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, type: String, message: String): Response =
        json(status, JSONObject().put("error", JSONObject().put("type", type).put("message", message)))
}
