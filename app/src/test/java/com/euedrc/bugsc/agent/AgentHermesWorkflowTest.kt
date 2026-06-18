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

    private class SequenceTransport(private val bodies: List<String>) : DeepSeekTransport {
        val requests = mutableListOf<DeepSeekHttpRequest>()
        private var index = 0

        override fun execute(request: DeepSeekHttpRequest): DeepSeekHttpResponse {
            requests += request
            val body = bodies.getOrElse(index) { bodies.last() }
            index += 1
            return DeepSeekHttpResponse(200, body)
        }
    }

    private fun assistantContent(text: String): String =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(text)}}}]}"""

    private fun assistantToolCall(name: String, arguments: String, id: String = "call_$name"): String =
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":""" +
            """[{"id":"$id","type":"function","function":{"name":"$name","arguments":${JSONObject.quote(arguments)}}}]}}]}"""

    @Test
    fun toolCallingLoopLetsModelChooseToolThenAnswerFromToolResult() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val events = mutableListOf<AgentRuntimeEvent>()
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("search_ship", """{"query":"C2"}"""),
                assistantContent("C2 Hercules 的货仓是 696 SCU。"),
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
            .let { messages -> (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).optString("content") } }
        assertTrue(secondPrompt.contains("\"type\":\"tool_result\""))
        assertTrue(secondPrompt.contains("C2 Hercules"))
        val secondMessages = JSONObject(transport.requests[1].body).getJSONArray("messages")
        val assistantToolMessage = (0 until secondMessages.length())
            .map { secondMessages.getJSONObject(it) }
            .first { it.getString("role") == "assistant" && it.has("tool_calls") }
        val toolCallId = assistantToolMessage.getJSONArray("tool_calls")
            .getJSONObject(0)
            .getString("id")
        val toolMessage = (0 until secondMessages.length())
            .map { secondMessages.getJSONObject(it) }
            .first { it.getString("role") == "tool" }
        assertEquals(toolCallId, toolMessage.getString("tool_call_id"))
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
            deepSeekClient = DeepSeekClient(SequenceTransport(listOf(assistantContent("我去查一下 C2")))),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("C2 货仓多大")

        assertTrue(answer.contains("没有查到"))
        assertTrue(answer.contains("C2 货仓多大"))
        assertTrue(ship.calls.isEmpty())
    }

    @Test
    fun casualConversationCanReturnFinalAnswerWithoutToolCall() = runBlocking {
        val tool = RecordingTool("list_my_orders", summary = "不应该被调用")
        val transport = SequenceTransport(
            listOf(assistantContent("我在，想查船、矿、市场，或者只是聊两句都行。")),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(tool)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("你在吗，陪我聊聊")

        assertTrue(answer.contains("我在"))
        assertTrue(tool.calls.isEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun casualConversationCanUsePlainNaturalModelAnswer() = runBlocking {
        val tool = RecordingTool("list_my_orders", summary = "不应该被调用")
        val transport = SequenceTransport(listOf(assistantContent("我在。你想聊配船、市场，还是今天随便巡一圈？")))
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(tool)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("你在吗")

        assertTrue(answer.contains("我在"))
        assertTrue(tool.calls.isEmpty())
        assertEquals(1, transport.requests.size)
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
                    listOf(assistantContent("""{"type":"unknown","answer":"未知输出类型"}""")),
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
                assistantToolCall("search_missing", """{"query":"C2"}"""),
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
                assistantToolCall("search_ship", "{}"),
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
    fun toolErrorIsFedBackSoModelCanCorrectAndAnswer() = runBlocking {
        val ship = RecordingTool("search_ship", summary = "C2 Hercules，货仓 696 SCU")
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("search_ship", """{"wrong":"C2"}""", id = "call_a"),
                assistantToolCall("search_ship", """{"query":"C2"}""", id = "call_b"),
                assistantContent("C2 Hercules 的货仓是 696 SCU。"),
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

        assertEquals("C2 Hercules 的货仓是 696 SCU。", answer)
        // 工具只在参数纠正后成功执行了一次
        assertEquals(listOf("C2"), ship.calls.map { it.args["query"] })
        // 三轮模型往返：错误调用 -> 纠正调用 -> 最终回答（没有在第一次错误就退出）
        assertEquals(3, transport.requests.size)
        val correctivePrompt = JSONObject(transport.requests[1].body)
            .getJSONArray("messages")
            .let { msgs -> (0 until msgs.length()).joinToString("\n") { msgs.getJSONObject(it).optString("content") } }
        assertTrue(correctivePrompt.contains("missing required arguments") || correctivePrompt.contains("参数不完整"))
    }

    @Test
    fun toolCallingLoopExposesToolEvidenceForFollowUps() = runBlocking {
        val market = RecordingTool("search_market", summary = "科粒晶 在售 2 个卖家")
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("search_market", """{"query":"科粒晶"}"""),
                assistantContent("科粒晶有 2 个卖家在售。"),
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

        val answer = runtime.answer("科粒晶有人卖吗")

        assertEquals("科粒晶有 2 个卖家在售。", answer)
        assertTrue(runtime.lastToolEvidence.contains("search_market"))
        assertTrue(runtime.lastToolEvidence.contains("科粒晶 在售 2 个卖家"))
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
                assistantToolCall("draft_scm_order", """{"query":"出售 绝杀步枪 1 个 单价 50000 地点 死局"}"""),
                assistantContent("已生成订单草稿，请确认后提交。"),
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
                assistantToolCall("search_market", """{"query":"科粒晶","side":"sell"}"""),
                assistantToolCall("search_market", """{"query":"科粒晶","side":"sell"}"""),
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
                    // 只给一个工具调用响应：执行成功后模型若再次发出同样调用会被去重，
                    // 触发 grounded fallback，从而验证多行挂单摘要被完整保留。
                    listOf(
                        assistantToolCall("search_market", """{"query":"asd","side":"sell"}"""),
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
    fun myOrdersQueryLetsModelChooseToolThenAnswerFromResult() = runBlocking {
        val calls = mutableListOf<AgentToolCall>()
        val myOrders = object : AgentTool {
            override val name: String = "list_my_orders"
            override val description: String = "查询当前登录用户自己的 SCM 挂单"
            override val parameters: List<AgentToolParameter> = emptyList()

            override suspend fun run(call: AgentToolCall): AgentToolResult {
                calls += call
                return AgentToolResult(
                    call = call,
                    summary = "OWN-1，出售 科力晶，1200 aUEC ×3，上架",
                    facts = listOf(AgentFact("我的订单", "OWN-1")),
                    sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                    confidence = 0.82f,
                )
            }
        }
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("list_my_orders", "{}"),
                assistantContent("我的挂单\n\nOWN-1，出售 科力晶，1200 aUEC ×3，上架"),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(myOrders)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("我有什么订单")

        assertTrue(answer.contains("OWN-1"))
        assertEquals(1, calls.size)
        assertEquals("list_my_orders", calls.single().tool)
        assertEquals("model_tool_call", calls.single().reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun myPublishedOrdersQueryIsNotTreatedAsCreateOrderRequest() = runBlocking {
        val calls = mutableListOf<AgentToolCall>()
        val myOrders = object : AgentTool {
            override val name: String = "list_my_orders"
            override val description: String = "查询当前登录用户自己的 SCM 挂单"
            override val parameters: List<AgentToolParameter> = emptyList()

            override suspend fun run(call: AgentToolCall): AgentToolResult {
                calls += call
                return AgentToolResult(
                    call = call,
                    summary = "OWN-1，出售 科力晶，1200 aUEC ×3，上架",
                    facts = listOf(AgentFact("我的订单", "OWN-1")),
                    sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                    confidence = 0.82f,
                )
            }
        }
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("list_my_orders", "{}"),
                assistantContent("我的挂单\n\nOWN-1，出售 科力晶，1200 aUEC ×3，上架"),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(myOrders)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("我发布了什么")

        assertTrue(answer.contains("OWN-1"))
        assertEquals(1, calls.size)
        assertEquals("model_tool_call", calls.single().reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun myTransactionsQueryLetsModelChooseMarketActivityToolThenAnswerFromResult() = runBlocking {
        val calls = mutableListOf<AgentToolCall>()
        val activity = object : AgentTool {
            override val name: String = "list_my_market_activity"
            override val description: String = "查询我的 SCM 挂单和交易记录"
            override val parameters: List<AgentToolParameter> = emptyList()

            override suspend fun run(call: AgentToolCall): AgentToolResult {
                calls += call
                return AgentToolResult(
                    call = call,
                    summary = "我的挂单\nOWN-1，出售 科力晶\n我的交易\nTR-BUY-1，我买入 科粒晶",
                    facts = listOf(AgentFact("我的交易", "TR-BUY-1")),
                    sources = listOf(AgentSource("SCM 我的市场活动", "remote")),
                    confidence = 0.82f,
                )
            }
        }
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("list_my_market_activity", "{}"),
                assistantContent("整理好了：\n\n我的挂单\nOWN-1，出售 科力晶\n\n我的交易\nTR-BUY-1，我买入 科粒晶"),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(activity)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("查我的交易")

        assertTrue(answer.contains("我的挂单"))
        assertTrue(answer.contains("我的交易"))
        assertTrue(answer.contains("\n\n"))
        assertEquals(1, calls.size)
        assertEquals("list_my_market_activity", calls.single().tool)
        assertEquals("model_tool_call", calls.single().reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun signInQueryLetsModelChooseSignInToolThenAnswerFromResult() = runBlocking {
        val calls = mutableListOf<AgentToolCall>()
        val signIn = object : AgentTool {
            override val name: String = "scm_sign_in"
            override val description: String = "执行或查询 SCM 每日签到"
            override val parameters: List<AgentToolParameter> = listOf(
                AgentToolParameter("action", "sign 执行签到；status 只查询签到状态", required = false),
            )

            override suspend fun run(call: AgentToolCall): AgentToolResult {
                calls += call
                return AgentToolResult(
                    call = call,
                    summary = "签到成功，连续签到 3 天，累计 10 天",
                    facts = listOf(AgentFact("签到结果", "成功")),
                    sources = listOf(AgentSource("SCM 签到", "remote")),
                    confidence = 0.82f,
                )
            }
        }
        val transport = SequenceTransport(
            listOf(
                assistantToolCall("scm_sign_in", """{"action":"sign"}"""),
                assistantContent("签到成功。\n\n连续签到 3 天，累计 10 天。"),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(signIn)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("帮我签到")

        assertTrue(answer.contains("签到成功"))
        assertEquals(1, calls.size)
        assertEquals("scm_sign_in", calls.single().tool)
        assertEquals("model_tool_call", calls.single().reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun invalidModelOutputFallsBackToDeterministicOwnOrdersTool() = runBlocking {
        val calls = mutableListOf<AgentToolCall>()
        val myOrders = object : AgentTool {
            override val name: String = "list_my_orders"
            override val description: String = "查询当前登录用户自己的 SCM 挂单"
            override val parameters: List<AgentToolParameter> = emptyList()

            override suspend fun run(call: AgentToolCall): AgentToolResult {
                calls += call
                return AgentToolResult(
                    call = call,
                    summary = "OWN-1，出售 科力晶，1200 aUEC ×3，上架",
                    facts = listOf(AgentFact("我的订单", "OWN-1")),
                    sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                    confidence = 0.82f,
                )
            }
        }
        val transport = SequenceTransport(
            listOf(
                assistantContent("我先帮你查一下"),
                assistantContent("我的挂单\n\nOWN-1，出售 科力晶，1200 aUEC ×3，上架"),
            ),
        )
        val runtime = AgentRuntime(
            analyzer = QueryAnalyzer(),
            toolRegistry = AgentToolRegistry(listOf(myOrders)),
            promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
            deepSeekClient = DeepSeekClient(transport),
            settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
            toolCallingEnabled = true,
        )

        val answer = runtime.answer("我有什么订单")

        assertTrue(answer.contains("OWN-1"))
        assertEquals(1, calls.size)
        assertEquals("deterministic_my_orders", calls.single().reason)
        assertEquals(2, transport.requests.size)
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
