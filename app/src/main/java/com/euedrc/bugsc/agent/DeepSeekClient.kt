package com.euedrc.bugsc.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeepSeekMessage(
    val role: String,
    val content: String,
)

data class DeepSeekHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

data class DeepSeekHttpResponse(
    val code: Int,
    val body: String,
)

interface DeepSeekTransport {
    fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse
}

class UrlConnectionDeepSeekTransport : DeepSeekTransport {
    override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return DeepSeekHttpResponse(code, body)
    }
}

class DeepSeekClientException(
    val userMessage: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)

class DeepSeekClient(
    private val transport: DeepSeekTransport,
    private val baseUrl: String = BASE_URL,
) {

    fun chat(settings: AgentSettings, messages: List<DeepSeekMessage>): String {
        if (settings.apiKey.isBlank()) {
            throw DeepSeekClientException("请先配置 DeepSeek API Key")
        }
        val body = JSONObject()
            .put("model", settings.model.ifBlank { AgentSettingsStore.MODEL_DEEPSEEK_FLASH })
            .put(
                "messages",
                JSONArray().also { arr ->
                    messages.forEach { msg ->
                        arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                },
            )
            .put("stream", false)
            .toString()
        val resp = transport.execute(
            DeepSeekHttpRequest(
                url = baseUrl.trimEnd('/') + "/chat/completions",
                headers = mapOf(
                    "Authorization" to "Bearer ${settings.apiKey}",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = body,
            ),
        )
        if (resp.code !in 200..299) {
            throw DeepSeekClientException(mapError(resp.code, resp.body))
        }
        val json = runCatching { JSONObject(resp.body) }.getOrElse {
            throw DeepSeekClientException("DeepSeek 返回格式无法解析", it)
        }
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        if (content.isBlank()) throw DeepSeekClientException("DeepSeek 返回了空回答")
        return content
    }

    fun testConnection(settings: AgentSettings): Boolean =
        runCatching {
            chat(settings, listOf(DeepSeekMessage("user", "ping")))
            true
        }.getOrDefault(false)

    private fun mapError(code: Int, body: String): String = when (code) {
        401, 403 -> "DeepSeek API Key 无效或无权限，请检查 Key"
        402, 429 -> "DeepSeek 额度不足或请求过于频繁，请稍后重试"
        in 500..599 -> "DeepSeek 服务暂不可用，请稍后重试"
        else -> "DeepSeek 请求失败：HTTP $code ${body.take(80)}"
    }

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
    }
}
