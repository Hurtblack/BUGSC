package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.transaction.AddressNode
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

    private class SequenceTransport(private val contents: List<String>) : DeepSeekTransport {
        val requests = mutableListOf<DeepSeekHttpRequest>()
        private var index = 0

        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            requests += request
            val content = contents.getOrElse(index) { contents.last() }
            index += 1
            return DeepSeekHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}}}]}""")
        }
    }

    @Test
    fun toolCallingLoopLetsModelChooseToolThenAnswerFromToolResult() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val events = mutableListOf<AgentRuntimeEvent>()
        val transport = SequenceTransport(
            listOf(
                """{"type":"tool_call","tool":"search_ship","arguments":{"query":"C2"}}""",
                """{"type":"final_answer","answer":"C2 Hercules 的货仓是 696 SCU。"}""",
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(ship)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
            observer = events::add,
        )

        val answer = runtime.answer("C2 货仓多大")

        assertEquals("C2 Hercules 的货仓是 696 SCU。", answer)
        assertEquals(listOf("C2"), ship.calls.map { it.args["query"] })
        assertEquals(2, transport.requests.size)
        val secondPrompt = JSONObject(transport.requests[1].body)
            .getJSONArray("messages")
            .let { messages -> (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).getString("content") } }
        assertTrue(secondPrompt.contains("\"type\":\"tool_result\""))
        assertTrue(secondPrompt.contains("C2 Hercules"))
        assertTrue(events.any { it is AgentRuntimeEvent.Thinking })
        assertTrue(events.any { it is AgentRuntimeEvent.ModelJson && it.content.contains("tool_call") })
        assertTrue(events.any { it is AgentRuntimeEvent.ToolCallStarted && it.tool == "search_ship" })
        assertTrue(events.any { it is AgentRuntimeEvent.FinalAnswerReady })
    }

    @Test
    fun invalidToolCallingJsonReturnsNoResultFallback() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(ship)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(SequenceTransport(listOf("我去查一下 C2"))),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("C2 货仓多大")

        assertTrue(answer.contains("没有查到"))
        assertTrue(answer.contains("C2 货仓多大"))
        assertTrue(ship.calls.isEmpty())
    }

    @Test
    fun unknownModelOutputTypeReturnsNoResultFallbackInsteadOfLeakingParserError() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(ship)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(
                SequenceTransport(
                    listOf("""{"type":"unknown","answer":"未知输出类型"}"""),
                ),
            ),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("科粒晶")

        assertTrue(answer.contains("没有查到"))
        assertTrue(answer.contains("科粒晶"))
        assertTrue(!answer.contains("未知输出类型"))
        assertTrue(ship.calls.isEmpty())
    }

    @Test
    fun unknownToolCallReturnsNoResultFallback() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val transport = SequenceTransport(
            listOf(
                """{"type":"tool_call","tool":"search_missing","arguments":{"query":"C2"}}""",
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(ship)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("C2 货仓多大")

        assertTrue(answer.contains("没有查到"))
        assertTrue(answer.contains("C2 货仓多大"))
        assertTrue(!answer.contains("search_missing"))
        assertTrue(ship.calls.isEmpty())
    }

    @Test
    fun missingToolArgumentReturnsNoResultFallback() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val transport = SequenceTransport(
            listOf(
                """{"type":"tool_call","tool":"search_ship","arguments":{}}""",
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(ship)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("C2 货仓多大")

        assertTrue(answer.contains("没有查到"))
        assertTrue(answer.contains("C2 货仓多大"))
        assertTrue(ship.calls.isEmpty())
    }

    @Test
    fun toolCallingLoopCanCreateScmOrderDraftForUiConfirmation() = runBlocking {
        val draftState = ScmOrderDraftToolState()
        val draftTool = ScmOrderDraftTool(
            state = draftState,
            isLoggedIn = { true },
            itemSearch = {
                listOf(
                    ItemSearchResult(
                        id = 42,
                        itemName = "绝杀步枪",
                        itemNameEn = "Killshot Rifle",
                        thumbnailUrl = "",
                        thumbnailUrlHd = "",
                    ),
                )
            },
            addressList = { listOf(AddressNode(id = 7, parentId = 0, name = "死局空间站")) },
        )
        val transport = SequenceTransport(
            listOf(
                """{"type":"tool_call","tool":"draft_scm_order","arguments":{"query":"出售 绝杀步枪 1 个 单价 50000 地点 死局"}}""",
                """{"type":"final_answer","answer":"已生成订单草稿，请确认后提交。"}""",
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(draftTool)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("帮我挂单卖绝杀步枪")

        assertEquals("已生成订单草稿，请确认后提交。", answer)
        assertTrue(draftState.resolved != null)
        assertEquals("死局空间站", draftState.resolved?.location?.name)
    }

    @Test
    fun repeatedToolCallReturnsGroundedFallbackInsteadOfMaxStepError() = runBlocking {
        val market = RecordingTool("search_market", summary = "科粒晶 出售 1200 aUEC ×10")
        val transport = SequenceTransport(
            listOf(
                """{"type":"tool_call","tool":"search_market","arguments":{"query":"科粒晶","side":"sell"}}""",
                """{"type":"tool_call","tool":"search_market","arguments":{"query":"科粒晶","side":"sell"}}""",
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(market)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("帮我查一下科粒晶有没有人卖")

        assertTrue(answer.contains("科粒晶"))
        assertTrue(answer.contains("1200"))
        assertTrue(!answer.contains("max steps"))
        assertEquals(1, market.calls.size)
    }

    @Test
    fun marketToolFallbackKeepsAllOrderSummaryLines() = runBlocking {
        val market = object : AgentTool {
            override val name: String = "search_market"
            override val description: String = "SCM 市场订单查询"

            override suspend fun run(call: AgentToolCall): AgentToolResult = AgentToolResult(
                call = call,
                summary = "联科发安全驱动器 (红色)，出售 500000 aUEC ×10，卖家 seller，地点 列夫斯基\n" +
                    "联科发安全驱动器 (红色)，出售 700000 aUEC ×5，卖家 seller2，地点 埃弗勒斯空间站",
                facts = listOf(
                    AgentFact("订单", "CSDD1"),
                    AgentFact("卖家", "seller"),
                    AgentFact("单价", "500000 aUEC"),
                    AgentFact("地点", "列夫斯基"),
                    AgentFact("订单", "CSDD2"),
                    AgentFact("卖家", "seller2"),
                    AgentFact("单价", "700000 aUEC"),
                    AgentFact("地点", "埃弗勒斯空间站"),
                ),
                sources = listOf(AgentSource("SCM 市场 API", "remote")),
                confidence = 0.72f,
            )
        }
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(market)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(
                SequenceTransport(
                    listOf(
                        """{"type":"tool_call","tool":"search_market","arguments":{"query":"asd","side":"sell"}}""",
                        """模型自然语言错误""",
                    ),
                ),
            ),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("asd相关的东西呢")

        assertTrue(answer.contains("500000"))
        assertTrue(answer.contains("700000"))
        assertTrue(answer.contains("seller2"))
    }

    @Test
    fun globalIndexMatchesShortChineseAliasInsideTranslatedName() = runBlocking {
        val registry = AgentToolRegistry(
            AgentLocalSearchTools.create(
                provider = object : AgentSearchProvider {
                    override suspend fun search(query: AgentQuery): List<AgentSearchHit> = emptyList()
                },
                entityIndex = AgentEntityIndex(
                    entries = listOf(
                        AgentEntity("blueprint", "Killshot Rifle", "绝杀 步枪", listOf("绝杀 步枪", "Killshot Rifle")),
                    ),
                ),
            ),
        )

        val result = registry.execute(listOf(AgentToolCall("search_local_index", mapOf("term" to "绝杀")))).single()

        assertTrue(result.summary.contains("绝杀 步枪"))
        assertTrue(result.facts.any { it.value.contains("绝杀 步枪") })
    }

}
