package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSelfSkillTest {

    private val profile = AgentProfileProvider.defaultProfile()
    private val skill = AgentSelfSkill(profile)

    @Test
    fun matchesSelfHelpQuestionsWithHighPriority() {
        val query = AgentQuery(
            rawText = "你和 SCM 什么关系？",
            normalizedText = "你和 scm 什么关系",
            intents = listOf(ScoredIntent(AgentIntent.SELF_HELP, 10)),
            entities = emptyList(),
        )

        assertTrue(skill.match(query).score >= AgentSelfSkill.MATCH_SCORE)
    }

    @Test
    fun answerUsesProfileFactsForIdentityAndPrivacy() = runBlocking {
        val result = skill.execute(
            AgentQuery(
                rawText = "你是谁？我的 Key 会上传吗？",
                normalizedText = "你是谁 我的 key 会上传吗",
                intents = listOf(ScoredIntent(AgentIntent.SELF_HELP, 10)),
                entities = emptyList(),
            ),
        )

        val text = result.summary
        assertTrue(text.contains(profile.displayName))
        assertTrue(text.contains("不是 SCM 官方机器人"))
        assertTrue(text.contains("不是 SCM 后端用户"))
        assertTrue(text.contains("API Key"))
        assertTrue(text.contains("本机"))
        assertTrue(result.sources.any { it.name.contains("人物卡") })
    }
}
