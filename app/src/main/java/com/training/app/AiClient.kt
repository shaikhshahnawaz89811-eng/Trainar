package com.training.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiReply(val text: String, val provider: String, val httpCode: Int)

/** Real OpenAI-compatible HTTP client. It never returns fabricated responses. */
class AiClient(private val keys: ApiKeyStore) {
    suspend fun complete(provider: String, endpoint: String, model: String, prompt: String): AiReply {
        val key = keys.get(provider) ?: error("No API key saved for $provider")
        require(prompt.isNotBlank()) { "Prompt is required" }
        val url = URL(endpoint.trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("AI request failed (HTTP $code): ${response.take(500)}")
        val text = JSONObject(response).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return AiReply(text, provider, code)
    }
}
