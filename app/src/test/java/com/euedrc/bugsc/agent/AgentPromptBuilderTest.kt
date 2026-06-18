package com.euedrc.bugsc.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {

    private val profile = AgentProfileProvider.defaultProfile()
    private val builder = AgentPromptBuilder(profile)

    @Test
    fun promptIncludesProfileAndSkillFacts() {
        val messages = builder.build(
            userText = "量子矿哪里采",
            history = emptyList(),
            skillResults = listOf(
                SkillResult(
                    skillId = "mining",
                    summary = "Quantanium 可在 Lyria 采集",
                    facts = listOf(AgentFact("地点", "Lyria")),
                    sources = listOf(AgentSource("mining assets", "local")),
                    confidence = 0.9f,
                ),
            ),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains(profile.displayName))
        assertTrue(text.contains("人设"))
        assertTrue(text.contains("自然"))
        assertTrue(text.contains("Quantanium"))
        assertTrue(text.contains("Lyria"))
        assertFalse(text.contains("mining assets"))
        assertTrue(messages.any { it.role == "system" })
        assertTrue(messages.any { it.role == "user" })
    }

    @Test
    fun promptForbidsPseudoToolCalls() {
        val messages = builder.build(
            userText = "石英蓝图怎么弄",
            history = emptyList(),
            skillResults = listOf(
                SkillResult(
                    skillId = "blueprint",
                    summary = "蓝图资料 未命中相关数据",
                    facts = emptyList(),
                    sources = listOf(AgentSource("蓝图资料", "local")),
                    confidence = 0f,
                ),
            ),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("不要输出 <search>"))
        assertTrue(text.contains("不能假装正在联网查询"))
        assertTrue(text.contains("资料未命中不等于不能回答"))
    }

    @Test
    fun promptConstrainsOrderCreationToAppConfirmation() {
        val messages = builder.build(
            userText = "帮我创建一个出售订单",
            history = emptyList(),
            skillResults = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("订单类请求"))
        assertTrue(text.contains("只协助整理订单草稿"))
        assertTrue(text.contains("缺少出售或求购、物品、数量、单价、交易地点"))
        assertTrue(text.contains("不要声称订单已创建"))
        assertTrue(text.contains("用户点击确认"))
    }

    @Test
    fun toolCallingPromptStopsMarketSearchWhenMarketToolMisses() {
        val messages = builder.buildToolCalling(
            userText = "帮我查一下科粒晶有没有人卖",
            history = emptyList(),
            loopMessages = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("search_market"))
        assertTrue(text.contains("市场"))
        assertTrue(text.contains("直接告诉用户市场暂无"))
        assertTrue(text.contains("不要继续调用 search_scm_item、search_local_index、search_mining"))
    }

    @Test
    fun toolCallingPromptAllowsNaturalConversationWithoutTools() {
        val messages = builder.buildToolCalling(
            userText = "你是谁，能陪我聊聊吗",
            history = emptyList(),
            loopMessages = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("先判断"))
        assertTrue(text.contains("能直接回答的闲聊"))
        assertTrue(text.contains("不要为了显得会查而调用工具"))
    }

    @Test
    fun toolCallingPromptDescribesBroadMarketListingIntent() {
        val messages = builder.buildToolCalling(
            userText = "市场最近有什么货",
            history = emptyList(),
            loopMessages = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("市场列表意图"))
        assertTrue(text.contains("没有指定具体物品"))
        assertTrue(text.contains("query=\"\""))
        assertTrue(text.contains("side=\"sell\""))
        assertTrue(text.contains("side=\"buy\""))
    }

    @Test
    fun toolCallingPromptDescribesMarketFollowUpIntent() {
        val messages = builder.buildToolCalling(
            userText = "谁卖啊",
            history = emptyList(),
            loopMessages = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("市场追问意图"))
        assertTrue(text.contains("谁卖"))
        assertTrue(text.contains("卖家"))
        assertTrue(text.contains("search_market"))
    }

    @Test
    fun toolCallingPromptPreventsRepeatedToolCallAfterUsefulResult() {
        val messages = builder.buildToolCalling(
            userText = "Perseus wiki",
            history = emptyList(),
            loopMessages = listOf(
                DeepSeekMessage(
                    "assistant",
                    "",
                    toolCalls = listOf(DeepSeekToolCall("call_1", "search_ship", """{"query":"Perseus"}""")),
                ),
                DeepSeekMessage("tool", """{"type":"tool_result","tool":"search_ship","summary":"英仙座 / Perseus"}""", toolCallId = "call_1"),
            ),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("收到工具结果后"))
        assertTrue(text.contains("不要用相同参数重复调用同一个工具"))
    }

    @Test
    fun toolCallingPromptIncludesRecentToolEvidenceFromHistory() {
        val messages = builder.buildToolCalling(
            userText = "谁卖啊",
            history = listOf(
                AgentMessage(
                    id = "1",
                    role = AgentMessageRole.USER,
                    content = "科粒晶有人卖吗",
                    createdAt = 1L,
                    status = AgentMessageStatus.SENT,
                ),
                AgentMessage(
                    id = "2",
                    role = AgentMessageRole.ASSISTANT,
                    content = "科粒晶有 2 个卖家在售。",
                    createdAt = 2L,
                    status = AgentMessageStatus.SENT,
                    toolSummary = "search_market 摘要:科粒晶 在售 | 卖家:Alice；价格:120",
                ),
            ),
            loopMessages = emptyList(),
        )
        val text = messages.joinToString("\n") { it.content }

        assertTrue(text.contains("最近一次工具证据"))
        assertTrue(text.contains("search_market 摘要:科粒晶 在售"))
        assertTrue(text.contains("Alice"))
    }

    @Test
    fun promptFiltersSensitiveFields() {
        val messages = builder.build(
            userText = "token=secret Cookie=session DeepSeek API Key sk-test",
            history = listOf(
                AgentMessage(
                    id = "1",
                    role = AgentMessageRole.USER,
                    content = "Authorization: Bearer scm-token",
                    createdAt = 1L,
                    status = AgentMessageStatus.SENT,
                ),
            ),
            skillResults = listOf(
                SkillResult(
                    skillId = "remote",
                    summary = "Cookie: abc\napiKey=sk-test\nSCM token scm-token",
                    facts = listOf(AgentFact("token", "scm-token")),
                    sources = listOf(AgentSource("remote", "remote")),
                    confidence = 0.1f,
                ),
            ),
        )
        val text = messages.joinToString("\n") { it.content }

        assertFalse(text.contains("sk-test"))
        assertFalse(text.contains("scm-token"))
        assertFalse(text.contains("session"))
        assertTrue(text.contains("[已过滤]"))
    }
}
