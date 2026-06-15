package com.euedrc.bugsc.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {

    private class FakeTransport(private val response: DeepSeekHttpResponse) : DeepSeekTransport {
        lateinit var request: DeepSeekHttpRequest
        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            this.request = request
            return response
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
}
