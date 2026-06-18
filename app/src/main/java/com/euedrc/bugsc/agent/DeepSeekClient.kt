package com.euedrc.bugsc.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

data class DeepSeekMessage(
    val role: String,
    val content: String,
    val toolCalls: List<DeepSeekToolCall> = emptyList(),
    val toolCallId: String = "",
)

data class DeepSeekToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class DeepSeekChatResult(
    val content: String,
    val toolCalls: List<DeepSeekToolCall>,
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

data class DeepSeekStreamEvent(
    val data: String,
)

interface DeepSeekTransport {
    fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse
    fun executeStream(
        request: DeepSeekHttpRequest,
        onEvent: (DeepSeekStreamEvent) -> Unit,
    ): DeepSeekHttpResponse = execute(request)
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

    override fun executeStream(
        request: DeepSeekHttpRequest,
        onEvent: (DeepSeekStreamEvent) -> Unit,
    ): DeepSeekHttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return DeepSeekHttpResponse(code, body)
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("data:")) {
                    onEvent(DeepSeekStreamEvent(line.removePrefix("data:").trim()))
                }
            }
        }
        return DeepSeekHttpResponse(code, "")
    }
}

class DeepSeekClientException(
    val userMessage: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)

class DeepSeekClient(
    private val transport: DeepSeekTransport,
    private val baseUrl: String = BASE_URL,
    private val retryDelayMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val requestDeadlineMs: Long = DEFAULT_REQUEST_DEADLINE_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    fun chat(settings: AgentSettings, messages: List<DeepSeekMessage>): String {
        val message = postForMessage(settings, messages, emptyList())
        val content = message.optString("content").orEmpty().trim()
        if (content.isBlank()) throw DeepSeekClientException("DeepSeek 返回了空回答")
        return content
    }

    fun chatWithTools(
        settings: AgentSettings,
        messages: List<DeepSeekMessage>,
        tools: List<AgentToolDefinition>,
    ): DeepSeekChatResult {
        val message = postForMessage(settings, messages, tools)
        val content = message.optString("content").orEmpty().trim()
        val toolCalls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { index ->
                val call = arr.optJSONObject(index) ?: return@mapNotNull null
                val function = call.optJSONObject("function") ?: return@mapNotNull null
                val name = function.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                DeepSeekToolCall(
                    id = call.optString("id").ifBlank { "call_$index" },
                    name = name,
                    arguments = function.optString("arguments").ifBlank { "{}" },
                )
            }
        }.orEmpty()
        if (content.isBlank() && toolCalls.isEmpty()) throw DeepSeekClientException("DeepSeek 返回了空回答")
        return DeepSeekChatResult(content, toolCalls)
    }

    fun chatWithToolsStreaming(
        settings: AgentSettings,
        messages: List<DeepSeekMessage>,
        tools: List<AgentToolDefinition>,
        onDelta: (String) -> Unit,
    ): DeepSeekChatResult {
        val message = postForStreamingMessage(settings, messages, tools, onDelta)
        if (message.content.isBlank() && message.toolCalls.isEmpty()) throw DeepSeekClientException("DeepSeek 返回了空回答")
        return message
    }

    private fun postForMessage(
        settings: AgentSettings,
        messages: List<DeepSeekMessage>,
        tools: List<AgentToolDefinition>,
    ): JSONObject {
        if (settings.apiKey.isBlank()) {
            throw DeepSeekClientException("请先配置 DeepSeek API Key")
        }
        val request = buildRequest(settings, messages, tools, stream = false)
        val resp = executeWithRetries { transport.execute(request) }
        if (resp.code !in 200..299) {
            throw DeepSeekClientException(mapError(resp.code, resp.body))
        }
        val json = runCatching { JSONObject(resp.body) }.getOrElse {
            throw DeepSeekClientException("DeepSeek 返回格式无法解析", it)
        }
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw DeepSeekClientException("DeepSeek 返回格式无法解析")
    }

    private fun postForStreamingMessage(
        settings: AgentSettings,
        messages: List<DeepSeekMessage>,
        tools: List<AgentToolDefinition>,
        onDelta: (String) -> Unit,
    ): DeepSeekChatResult {
        if (settings.apiKey.isBlank()) {
            throw DeepSeekClientException("请先配置 DeepSeek API Key")
        }
        val request = buildRequest(settings, messages, tools, stream = true)
        val content = StringBuilder()
        val toolCalls = linkedMapOf<Int, MutableStreamToolCall>()
        var eventReceived = false
        val resp = executeWithRetries(canRetry = { !eventReceived }) {
            transport.executeStream(request) { event ->
                eventReceived = true
                mergeStreamEvent(event, content, toolCalls, onDelta)
            }
        }
        if (resp.code !in 200..299) {
            throw DeepSeekClientException(mapError(resp.code, resp.body))
        }
        if (!eventReceived && resp.body.isNotBlank()) {
            return parseChatResult(resp.body)
        }
        return DeepSeekChatResult(
            content = content.toString().trim(),
            toolCalls = toolCalls.toSortedMap().values.mapIndexedNotNull { fallbackIndex, call ->
                val name = call.name.toString().trim()
                if (name.isBlank()) return@mapIndexedNotNull null
                DeepSeekToolCall(
                    id = call.id.ifBlank { "call_$fallbackIndex" },
                    name = name,
                    arguments = call.arguments.toString().ifBlank { "{}" },
                )
            },
        )
    }

    private fun parseChatResult(body: String): DeepSeekChatResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw DeepSeekClientException("DeepSeek 返回格式无法解析", it)
        }
        val message = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw DeepSeekClientException("DeepSeek 返回格式无法解析")
        val content = message.optString("content").orEmpty().trim()
        val toolCalls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { index ->
                val call = arr.optJSONObject(index) ?: return@mapNotNull null
                val function = call.optJSONObject("function") ?: return@mapNotNull null
                val name = function.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                DeepSeekToolCall(
                    id = call.optString("id").ifBlank { "call_$index" },
                    name = name,
                    arguments = function.optString("arguments").ifBlank { "{}" },
                )
            }
        }.orEmpty()
        return DeepSeekChatResult(content, toolCalls)
    }

    private fun buildRequest(
        settings: AgentSettings,
        messages: List<DeepSeekMessage>,
        tools: List<AgentToolDefinition>,
        stream: Boolean,
    ): DeepSeekHttpRequest {
        val payload = JSONObject()
            .put("model", settings.model.ifBlank { AgentSettingsStore.MODEL_DEEPSEEK_FLASH })
            .put("messages", buildMessagesJson(messages))
            .put("stream", stream)
        if (tools.isNotEmpty()) {
            payload.put(
                "tools",
                JSONArray(
                    tools.map { tool ->
                        JSONObject().put("type", "function").put("function", tool.toJson())
                    },
                ),
            )
            payload.put("tool_choice", "auto")
        }
        return DeepSeekHttpRequest(
            url = baseUrl.trimEnd('/') + "/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${settings.apiKey}",
                "Content-Type" to "application/json",
                "Accept" to if (stream) "text/event-stream" else "application/json",
            ),
            body = payload.toString(),
        )
    }

    private fun mergeStreamEvent(
        event: DeepSeekStreamEvent,
        content: StringBuilder,
        toolCalls: MutableMap<Int, MutableStreamToolCall>,
        onDelta: (String) -> Unit,
    ) {
        val data = event.data.trim()
        if (data.isBlank() || data == "[DONE]") return
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        val choices = json.optJSONArray("choices") ?: return
        for (choiceIndex in 0 until choices.length()) {
            val delta = choices.optJSONObject(choiceIndex)?.optJSONObject("delta") ?: continue
            val piece = if (delta.has("content") && !delta.isNull("content")) {
                delta.optString("content")
            } else {
                ""
            }
            if (piece.isNotEmpty() && piece != "null") {
                content.append(piece)
                onDelta(piece)
            }
            val calls = delta.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                val obj = calls.optJSONObject(callIndex) ?: continue
                val index = obj.optInt("index", callIndex)
                val call = toolCalls.getOrPut(index) { MutableStreamToolCall() }
                val id = obj.optString("id")
                if (id.isNotBlank()) call.id = id
                val function = obj.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isNotBlank()) call.name.append(name)
                val arguments = function.optString("arguments")
                if (arguments.isNotEmpty()) call.arguments.append(arguments)
            }
        }
    }

    private fun executeWithRetries(
        canRetry: () -> Boolean = { true },
        block: () -> DeepSeekHttpResponse,
    ): DeepSeekHttpResponse {
        val startedAt = System.nanoTime()
        var attempt = 0
        while (true) {
            ensureWithinDeadline(startedAt)
            val response = runCatching { block() }
                .getOrElse { error ->
                    if (error is DeepSeekClientException) throw error
                    if (attempt < retryDelayMs.size && isRetryable(error) && canRetry()) {
                        sleepBeforeRetry(attempt, startedAt)
                        attempt += 1
                        return@getOrElse null
                    }
                    throw DeepSeekClientException(mapTransportError(error), error)
                }
            if (response == null) continue
            if (response.code in 200..299) return response
            if (attempt < retryDelayMs.size && isRetryableHttp(response.code) && canRetry()) {
                sleepBeforeRetry(attempt, startedAt)
                attempt += 1
                continue
            }
            return response
        }
    }

    private fun sleepBeforeRetry(attempt: Int, startedAt: Long) {
        val delayMs = retryDelayMs.getOrElse(attempt) { 0L }.coerceAtLeast(0L)
        val remainingMs = remainingDeadlineMs(startedAt)
        val boundedDelay = min(delayMs, remainingMs)
        if (boundedDelay > 0L) sleeper(boundedDelay)
    }

    private fun ensureWithinDeadline(startedAt: Long) {
        if (remainingDeadlineMs(startedAt) <= 0L) {
            throw DeepSeekClientException("DeepSeek 请求超时，请稍后重试")
        }
    }

    private fun remainingDeadlineMs(startedAt: Long): Long {
        if (requestDeadlineMs <= 0L) return Long.MAX_VALUE
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        return requestDeadlineMs - elapsedMs
    }

    private fun buildMessagesJson(messages: List<DeepSeekMessage>): JSONArray =
        JSONArray().also { arr ->
            messages.forEach { msg ->
                val obj = JSONObject().put("role", msg.role)
                when {
                    msg.toolCalls.isNotEmpty() -> {
                        obj.put(
                            "tool_calls",
                            JSONArray(
                                msg.toolCalls.map { call ->
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", call.arguments),
                                        )
                                },
                            ),
                        )
                        obj.put("content", if (msg.content.isBlank()) JSONObject.NULL else msg.content)
                    }
                    msg.role == "tool" -> {
                        obj.put("content", msg.content)
                        obj.put("tool_call_id", msg.toolCallId)
                    }
                    else -> obj.put("content", msg.content)
                }
                arr.put(obj)
            }
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

    private fun mapTransportError(error: Throwable): String = when (error) {
        is IOException -> "DeepSeek 连接中断，请检查网络后重试"
        else -> "DeepSeek 请求失败，请稍后重试"
    }

    private fun isRetryable(error: Throwable): Boolean =
        error is IOException

    private fun isRetryableHttp(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private class MutableStreamToolCall {
        var id: String = ""
        val name: StringBuilder = StringBuilder()
        val arguments: StringBuilder = StringBuilder()
    }

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
        private const val DEFAULT_REQUEST_DEADLINE_MS = 90_000L
        private val DEFAULT_RETRY_DELAYS_MS = listOf(400L, 900L)
    }
}
