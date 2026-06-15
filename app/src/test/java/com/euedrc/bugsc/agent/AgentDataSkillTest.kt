package com.euedrc.bugsc.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDataSkillTest {

    private class FakeProvider(
        private val hits: List<AgentSearchHit>,
    ) : AgentSearchProvider {
        override suspend fun search(query: AgentQuery): List<AgentSearchHit> = hits
    }

    private class FailingRemoteClient : RemoteQueryClient {
        override suspend fun query(query: AgentQuery, allowAuthenticated: Boolean): List<AgentSearchHit> {
            error("api timeout")
        }
    }

    @Test
    fun shipSkillReturnsShipFactsWithSource() = runBlocking {
        val skill = ShipSkill(
            FakeProvider(
                listOf(
                    AgentSearchHit(
                        summary = "F7A Hornet Mk II 火力强",
                        facts = listOf(AgentFact("武器", "S3 x4")),
                        sources = listOf(AgentSource("shipfit", "local")),
                        confidence = 0.9f,
                    ),
                ),
            ),
        )

        val result = skill.execute(query(AgentIntent.SHIP_INFO))

        assertEquals("ship", result.skillId)
        assertTrue(result.summary.contains("F7A"))
        assertTrue(result.facts.any { it.label == "武器" })
        assertEquals("shipfit", result.sources.first().name)
    }

    @Test
    fun localDataSkillsReportNoResultWithoutThrowing() = runBlocking {
        val result = MiningSkill(FakeProvider(emptyList())).execute(query(AgentIntent.MINING))

        assertEquals("mining", result.skillId)
        assertTrue(result.summary.contains("未命中"))
        assertEquals(0f, result.confidence)
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun blueprintMissionAndWikeloSkillsExposeExpectedIds() {
        assertEquals("blueprint", BlueprintSkill(FakeProvider(emptyList())).id)
        assertEquals("mission", MissionSkill(FakeProvider(emptyList())).id)
        assertEquals("wikelo", WikeloSkill(FakeProvider(emptyList())).id)
    }

    @Test
    fun scmQuerySkillUsesLoginStateButDoesNotRequireLogin() = runBlocking {
        val client = object : RemoteQueryClient {
            var seenAuth: Boolean? = null
            override suspend fun query(query: AgentQuery, allowAuthenticated: Boolean): List<AgentSearchHit> {
                seenAuth = allowAuthenticated
                return listOf(
                    AgentSearchHit(
                        summary = "SCM 市场有相关结果",
                        facts = listOf(AgentFact("市场", "有售")),
                        sources = listOf(AgentSource("SCM 查询 API", "remote")),
                        confidence = 0.7f,
                    ),
                )
            }
        }
        val skill = ScmQuerySkill(client, isScmLoggedIn = { true })

        val result = skill.execute(query(AgentIntent.MARKET))

        assertEquals("scm_query", result.skillId)
        assertEquals(true, client.seenAuth)
        assertTrue(result.summary.contains("SCM"))
    }

    @Test
    fun remoteQueryFailureReturnsErrorResult() = runBlocking {
        val result = ScmQuerySkill(FailingRemoteClient(), isScmLoggedIn = { false })
            .execute(query(AgentIntent.MARKET))

        assertEquals("scm_query", result.skillId)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("api timeout"))
        assertTrue(result.summary.contains("暂不可用"))
    }

    private fun query(intent: AgentIntent): AgentQuery = AgentQuery(
        rawText = "test",
        normalizedText = "test",
        intents = listOf(ScoredIntent(intent, 10)),
        entities = emptyList(),
    )
}
