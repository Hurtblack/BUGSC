package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHermesWorkflowTest {

    private class RecordingTool(
        override val name: String,
        override val description: String = name,
        private val summary: String = "$name result",
    ) : AgentTool {
        val calls = mutableListOf<AgentToolCall>()

        override suspend fun run(call: AgentToolCall): AgentToolResult {
            calls += call
            return AgentToolResult(
                call = call,
                summary = summary,
                facts = listOf(AgentFact("term", call.args["term"].orEmpty())),
                sources = listOf(AgentSource(name, "test")),
                confidence = 0.3f,
            )
        }
    }

    private class RecordingTransport : DeepSeekTransport {
        lateinit var request: DeepSeekHttpRequest
        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            this.request = request
            return DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"按工具结果总结"}}]}""")
        }
    }

    @Test
    fun blueprintWorkflowPlansMultipleEvidenceTools() = runBlocking {
        val blueprint = RecordingTool("search_blueprint", summary = "蓝图未直接命中")
        val mission = RecordingTool("search_mission", summary = "任务未直接命中")
        val wikelo = RecordingTool("search_wikelo", summary = "维科洛未直接命中")
        val mining = RecordingTool("search_mining", summary = "材料线索未直接命中")
        val planner = AgentPlanner(AgentSkillCardProvider.defaultCards())
        val registry = AgentToolRegistry(listOf(blueprint, mission, wikelo, mining))
        val transport = RecordingTransport()
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            planner = planner,
            toolRegistry = registry,
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
        )

        runtime.answer("石英怎么做")

        assertEquals(listOf("石英"), blueprint.calls.map { it.args["term"] })
        assertEquals(listOf("石英"), mission.calls.map { it.args["term"] })
        assertEquals(listOf("石英"), wikelo.calls.map { it.args["term"] })
        assertEquals(listOf("石英"), mining.calls.map { it.args["term"] })

        val requestJson = JSONObject(transport.request.body)
        val prompt = (0 until requestJson.getJSONArray("messages").length())
            .joinToString("\n") { requestJson.getJSONArray("messages").getJSONObject(it).getString("content") }
        assertTrue(prompt.contains("Skill 工作流：blueprint-crafting"))
        assertTrue(prompt.contains("工具执行记录"))
        assertTrue(prompt.contains("search_blueprint"))
        assertTrue(prompt.contains("资料未命中不等于不能回答"))
    }

    @Test
    fun pseudoToolCallStillFallsBackToObservations() = runBlocking {
        val blueprint = RecordingTool("search_blueprint", summary = "Blueprint evidence")
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            planner = AgentPlanner(AgentSkillCardProvider.defaultCards()),
            toolRegistry = AgentToolRegistry(listOf(blueprint)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(object : DeepSeekTransport {
                override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse =
                    DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"<search><query>石英</query></search>"}}]}""")
            }),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
        )

        val answer = runtime.answer("石英蓝图怎么弄")

        assertTrue(answer.contains("Blueprint evidence"))
        assertTrue(!answer.contains("<search>"))
    }
}
