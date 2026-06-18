package com.euedrc.bugsc.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class DeepSeekClientTest {

    private class FakeTransport(private val response: DeepSeekHttpResponse) : DeepSeekTransport {
        lateinit var request: DeepSeekHttpRequest
        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            this.request = request
            return response
        }
    }

    private class FailingTransport(private val error: Throwable) : DeepSeekTransport {
        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            throw error
        }
    }

    private class SequenceTransport(private val responses: List<DeepSeekHttpResponse>) : DeepSeekTransport {
        val requests = mutableListOf<DeepSeekHttpRequest>()
        private var index = 0

        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            requests += request
            val response = responses.getOrElse(index) { responses.last() }
            index += 1
            return response
        }
    }

    private class StreamTransport(private val events: List<String>) : DeepSeekTransport {
        lateinit var streamRequest: DeepSeekHttpRequest

        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            throw AssertionError("streaming API should not use non-stream execute")
        }

        override fun executeStream(
            request: DeepSeekHttpRequest,
            onEvent: (DeepSeekStreamEvent) -> Unit,
        ): DeepSeekHttpResponse {
            streamRequest = request
            events.forEach { onEvent(DeepSeekStreamEvent(it)) }
            return DeepSeekHttpResponse(200, "")
        }
    }

    @Test
    fun chatBuildsOpenAiCompatibleRequestAndParsesAssistantContent() {
        val transport = FakeTransport(
            DeepSeekHttpResponse(
                code = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":"回答内容"}}]}""",
            ),
        )
        val client = DeepSeekClient(transport)

        val content = client.chat(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "你好")),
        )

        assertEquals("回答内容", content)
        assertEquals("https://api.deepseek.com/chat/completions", transport.request.url)
        assertEquals("Bearer sk-test", transport.request.headers["Authorization"])
        val json = JSONObject(transport.request.body)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, json.getString("model"))
        assertEquals("user", json.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("你好", json.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun chatWithToolsSendsToolSchemaAndParsesToolCalls() {
        val transport = FakeTransport(
            DeepSeekHttpResponse(
                code = 200,
                body = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":""" +
                    """[{"id":"call_1","type":"function","function":{"name":"search_ship","arguments":"{\"query\":\"C2\"}"}}]}}]}""",
            ),
        )
        val client = DeepSeekClient(transport)

        val result = client.chatWithTools(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "C2 货仓多大")),
            tools = listOf(
                AgentToolDefinition(
                    name = "search_ship",
                    description = "飞船资料查询",
                    parameters = listOf(AgentToolParameter("query", "关键词")),
                ),
            ),
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals("search_ship", result.toolCalls.single().name)
        assertEquals("call_1", result.toolCalls.single().id)
        assertEquals("""{"query":"C2"}""", result.toolCalls.single().arguments)
        val body = JSONObject(transport.request.body)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("search_ship", tool.getJSONObject("function").getString("name"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun chatWithToolsSerializesAssistantToolCallsAndToolResultMessages() {
        val transport = FakeTransport(
            DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"好的"}}]}"""),
        )
        val client = DeepSeekClient(transport)

        val result = client.chatWithTools(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(
                DeepSeekMessage("user", "C2 货仓多大"),
                DeepSeekMessage(
                    "assistant",
                    "",
                    toolCalls = listOf(DeepSeekToolCall("call_1", "search_ship", """{"query":"C2"}""")),
                ),
                DeepSeekMessage("tool", """{"summary":"C2 货仓 696"}""", toolCallId = "call_1"),
            ),
            tools = emptyList(),
        )

        assertEquals("好的", result.content)
        assertTrue(result.toolCalls.isEmpty())
        val messages = JSONObject(transport.request.body).getJSONArray("messages")
        val assistant = messages.getJSONObject(1)
        assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        val toolMessage = messages.getJSONObject(2)
        assertEquals("tool", toolMessage.getString("role"))
        assertEquals("call_1", toolMessage.getString("tool_call_id"))
    }

    @Test
    fun authFailureMapsToReadableError() {
        val client = DeepSeekClient(FakeTransport(DeepSeekHttpResponse(401, """{"error":"bad key"}""")))

        val error = runCatching {
            client.chat(
                settings = AgentSettings(apiKey = "bad-key", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
                messages = listOf(DeepSeekMessage("user", "hi")),
            )
        }.exceptionOrNull()

        assertTrue(error is DeepSeekClientException)
        assertTrue((error as DeepSeekClientException).userMessage.contains("API Key"))
    }

    @Test
    fun transportAbortMapsToReadableErrorInsteadOfRawSocketMessage() {
        val client = DeepSeekClient(FailingTransport(SocketException("Software caused connection abort")))

        val error = runCatching {
            client.chat(
                settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
                messages = listOf(DeepSeekMessage("user", "我有什么订单")),
            )
        }.exceptionOrNull()

        assertTrue(error is DeepSeekClientException)
        val message = (error as DeepSeekClientException).userMessage
        assertTrue(message.contains("连接"))
        assertTrue(!message.contains("Software caused connection abort"))
    }

    @Test
    fun transientServerFailureIsRetriedBeforeReturningAnswer() {
        val transport = SequenceTransport(
            listOf(
                DeepSeekHttpResponse(502, "bad gateway"),
                DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"恢复了"}}]}"""),
            ),
        )
        val client = DeepSeekClient(transport, retryDelayMs = listOf(0L))

        val answer = client.chat(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "hi")),
        )

        assertEquals("恢复了", answer)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun clientErrorsAreNotRetried() {
        val transport = SequenceTransport(
            listOf(
                DeepSeekHttpResponse(401, "bad key"),
                DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"不应出现"}}]}"""),
            ),
        )
        val client = DeepSeekClient(transport, retryDelayMs = listOf(0L))

        val error = runCatching {
            client.chat(
                settings = AgentSettings(apiKey = "bad-key", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
                messages = listOf(DeepSeekMessage("user", "hi")),
            )
        }.exceptionOrNull()

        assertTrue(error is DeepSeekClientException)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun clientErrorAfterRetryKeepsHttpSpecificMessage() {
        var attempts = 0
        val transport = object : DeepSeekTransport {
            override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
                attempts += 1
                if (attempts == 1) throw IOException("timeout")
                return DeepSeekHttpResponse(401, "bad key")
            }
        }
        val client = DeepSeekClient(transport, retryDelayMs = listOf(0L))

        val error = runCatching {
            client.chat(
                settings = AgentSettings(apiKey = "bad-key", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
                messages = listOf(DeepSeekMessage("user", "hi")),
            )
        }.exceptionOrNull()

        assertTrue(error is DeepSeekClientException)
        assertTrue((error as DeepSeekClientException).userMessage.contains("API Key"))
        assertEquals(2, attempts)
    }


    @Test
    fun transportIoFailureIsRetriedBeforeReturningAnswer() {
        var attempts = 0
        val transport = object : DeepSeekTransport {
            override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
                attempts += 1
                if (attempts == 1) throw IOException("timeout")
                return DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"重试成功"}}]}""")
            }
        }
        val client = DeepSeekClient(transport, retryDelayMs = listOf(0L))

        val answer = client.chat(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "hi")),
        )

        assertEquals("重试成功", answer)
        assertEquals(2, attempts)
    }

    @Test
    fun streamingChatAccumulatesDeltasAndReportsThem() {
        val transport = StreamTransport(
            listOf(
                """{"choices":[{"delta":{"content":"你"}}]}""",
                """{"choices":[{"delta":{"content":"好"}}]}""",
                "[DONE]",
            ),
        )
        val client = DeepSeekClient(transport)
        val deltas = mutableListOf<String>()

        val result = client.chatWithToolsStreaming(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "你好")),
            tools = emptyList(),
            onDelta = deltas::add,
        )

        assertEquals("你好", result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(listOf("你", "好"), deltas)
        val body = JSONObject(transport.streamRequest.body)
        assertTrue(body.getBoolean("stream"))
        assertEquals("text/event-stream", transport.streamRequest.headers["Accept"])
    }

    @Test
    fun streamingChatIgnoresNullContentDeltas() {
        val transport = StreamTransport(
            listOf(
                """{"choices":[{"delta":{"content":null}}]}""",
                """{"choices":[{"delta":{"content":"null"}}]}""",
                """{"choices":[{"delta":{"content":"今日 WB"}}]}""",
                "[DONE]",
            ),
        )
        val client = DeepSeekClient(transport)
        val deltas = mutableListOf<String>()

        val result = client.chatWithToolsStreaming(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "wb")),
            tools = emptyList(),
            onDelta = deltas::add,
        )

        assertEquals("今日 WB", result.content)
        assertEquals(listOf("今日 WB"), deltas)
    }

    @Test
    fun streamingChatAccumulatesToolCallArgumentChunks() {
        val transport = StreamTransport(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search_ship","arguments":"{\"query\""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"C2\"}"}}]}}]}""",
                "[DONE]",
            ),
        )
        val client = DeepSeekClient(transport)

        val result = client.chatWithToolsStreaming(
            settings = AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            messages = listOf(DeepSeekMessage("user", "C2")),
            tools = listOf(AgentToolDefinition("search_ship", "飞船资料查询", listOf(AgentToolParameter("query", "关键词")))),
            onDelta = {},
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls.single().id)
        assertEquals("search_ship", result.toolCalls.single().name)
        assertEquals("""{"query":"C2"}""", result.toolCalls.single().arguments)
    }
}
