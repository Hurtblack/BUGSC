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
        assertTrue(text.contains("Quantanium"))
        assertTrue(text.contains("Lyria"))
        assertTrue(text.contains("mining assets"))
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
