package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillRegistryTest {

    private class FakeSkill(
        override val id: String,
        override val name: String,
        private val score: Int,
        private val fail: Boolean = false,
    ) : AgentSkill {
        override fun match(query: AgentQuery): SkillMatch = SkillMatch(id, score, "test")

        override suspend fun execute(query: AgentQuery): SkillResult {
            if (fail) error("remote unavailable")
            return SkillResult(
                skillId = id,
                summary = "$name result",
                facts = listOf(AgentFact("name", name)),
                sources = listOf(AgentSource(name, "test")),
                confidence = 0.8f,
            )
        }
    }

    private val query = AgentQuery(
        rawText = "量子矿哪里采",
        normalizedText = "量子矿哪里采",
        intents = listOf(ScoredIntent(AgentIntent.MINING, 4)),
        entities = emptyList(),
    )

    @Test
    fun planLimitsToTopScoringPositiveSkills() {
        val registry = AgentSkillRegistry(
            listOf(
                FakeSkill("a", "A", 10),
                FakeSkill("b", "B", 0),
                FakeSkill("c", "C", 8),
                FakeSkill("d", "D", 6),
            ),
            maxSkills = 2,
        )

        val plan = registry.plan(query)

        assertEquals(listOf("a", "c"), plan.map { it.skill.id })
    }

    @Test
    fun executeKeepsOtherResultsWhenOneSkillFails() = runBlocking {
        val registry = AgentSkillRegistry(
            listOf(
                FakeSkill("local", "Local", 10),
                FakeSkill("remote", "Remote", 9, fail = true),
            ),
            maxSkills = 2,
        )

        val results = registry.execute(query)

        assertEquals(2, results.size)
        assertTrue(results.any { it.skillId == "local" && it.error == null })
        assertTrue(results.any { it.skillId == "remote" && it.error?.contains("remote unavailable") == true })
    }
}
