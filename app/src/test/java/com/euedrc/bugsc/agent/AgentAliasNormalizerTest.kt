package com.euedrc.bugsc.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAliasNormalizerTest {

    @Test
    fun expandsPerseusChineseConstellationAlias() {
        val aliases = AgentAliasNormalizer.expandAliases("Perseus", "英仙座")

        assertTrue(aliases.contains("英仙座"))
    }

    @Test
    fun queryAnalyzerMatchesPerseusByChineseConstellationAlias() {
        val query = QueryAnalyzer(
            AgentEntityIndex(
                listOf(
                    AgentEntity(
                        type = "ship",
                        value = "rsi_perseus",
                        displayName = "英仙座",
                        aliases = AgentAliasNormalizer.expandAliases("Perseus", "英仙座"),
                    ),
                ),
            ),
        ).analyze("帮我搜一下英仙座")

        assertTrue(query.entities.any { it.type == "ship" && it.value == "rsi_perseus" })
    }
}
