package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {

    private class FixedSkill(
        private val result: SkillResult,
    ) : AgentSkill {
        override val id: String = result.skillId
        override val name: String = result.skillId
        override fun match(query: AgentQuery): SkillMatch = SkillMatch(id, 10, "test")
        override suspend fun execute(query: AgentQuery): SkillResult = result
    }

    private class FakeTransport(private val content: String) : DeepSeekTransport {
        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse =
            DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}}}]}""")
    }

    private class StreamSequenceTransport(private val responses: List<List<String>>) : DeepSeekTransport {
        private var index = 0

        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            throw AssertionError("tool loop should use streaming")
        }

        override fun executeStream(
            request: DeepSeekHttpRequest,
            onEvent: (DeepSeekStreamEvent) -> Unit,
        ): DeepSeekHttpResponse {
            responses.getOrElse(index) { responses.last() }.forEach { onEvent(DeepSeekStreamEvent(it)) }
            index += 1
            return DeepSeekHttpResponse(200, "")
        }
    }

    private class EmptyTool : AgentTool {
        override val name: String = "search_ship"
        override val description: String = "飞船资料查询"

        override suspend fun run(call: AgentToolCall): AgentToolResult =
            AgentToolResult(
                call = call,
                summary = "飞船资料 未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource("飞船资料", "local")),
                confidence = 0f,
            )
    }

    @Test
    fun noResultDoesNotAskModelToSearchAgain() = runBlocking {
        val runtime = runtime(
            result = SkillResult(
                skillId = "blueprint",
                summary = "蓝图资料 未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource("蓝图资料", "local")),
                confidence = 0f,
            ),
            modelContent = """
                <search>
                <query>石英 蓝图</query>
                </search>
            """.trimIndent(),
        )

        val answer = runtime.answer("石英蓝图怎么弄")

        assertTrue(answer.contains("没有查到"))
        assertFalse(answer.contains("<search>"))
    }

    @Test
    fun noResultStillAllowsModelReasoning() = runBlocking {
        val runtime = runtime(
            result = SkillResult(
                skillId = "blueprint",
                summary = "蓝图资料 未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource("蓝图资料", "local")),
                confidence = 0f,
            ),
            modelContent = "本地资料没有直接命中石英蓝图；按制作问题分析，你应先确认石英是否是材料名还是成品名。",
        )

        val answer = runtime.answer("石英怎么做")

        assertTrue(answer.contains("按制作问题分析"))
    }

    @Test
    fun pseudoToolCallFromModelIsReplacedWithGroundedAnswer() = runBlocking {
        val runtime = runtime(
            result = SkillResult(
                skillId = "mining",
                summary = "Quantanium 可在 Lyria 采集",
                facts = listOf(AgentFact("地点", "Lyria")),
                sources = listOf(AgentSource("mining assets", "local")),
                confidence = 0.9f,
            ),
            modelContent = "<search><query>Quantanium</query></search>",
        )

        val answer = runtime.answer("量子矿哪里采")

        assertTrue(answer.contains("Quantanium"))
        assertTrue(answer.contains("Lyria"))
        assertFalse(answer.contains("<search>"))
    }

    @Test
    fun toolSelectionDeltasAreNotShownAsFinalAnswerDraft() = runBlocking {
        val events = mutableListOf<AgentRuntimeEvent>()
        val transport = StreamSequenceTransport(
            listOf(
                listOf(
                    streamDelta(content = "你好，这个可以这样看。"),
                    streamDelta(toolName = "search_ship", arguments = """{"query":"不存在"}"""),
                    "[DONE]",
                ),
                listOf(
                    streamDelta(content = "没有查到可靠资料。"),
                    "[DONE]",
                ),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(EmptyTool())),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
            observer = events::add,
        )

        runtime.answer("不存在的船是什么")

        val deltas = events.filterIsInstance<AgentRuntimeEvent.AnswerDelta>().map { it.delta }
        assertFalse(deltas.any { it.contains("你好，这个可以这样看") })
    }

    private fun runtime(result: SkillResult, modelContent: String): AgentRuntime =
        AgentRuntime(
            analyzer = QueryAnalyzer(),
            skillRegistry = AgentSkillRegistry(listOf(FixedSkill(result))),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(FakeTransport(modelContent)),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
        )

    private fun streamDelta(content: String? = null, toolName: String? = null, arguments: String = "{}"): String {
        val delta = JSONObject()
        if (content != null) delta.put("content", content)
        if (toolName != null) {
            delta.put(
                "tool_calls",
                org.json.JSONArray(
                    listOf(
                        JSONObject()
                            .put("index", 0)
                            .put("id", "call_1")
                            .put("type", "function")
                            .put("function", JSONObject().put("name", toolName).put("arguments", arguments)),
                    ),
                ),
            )
        }
        return JSONObject()
            .put("choices", org.json.JSONArray(listOf(JSONObject().put("delta", delta))))
            .toString()
    }
}
