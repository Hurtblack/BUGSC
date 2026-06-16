package com.euedrc.bugsc.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLocalDataProviderInstrumentedTest {

    @Test
    fun chineseQuartzFindsBlueprintsUsingQuartzMaterial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalAgentDataProvider(context)

        val hits = provider.search(
            AgentQuery(
                rawText = "石英",
                normalizedText = "石英",
                intents = listOf(ScoredIntent(AgentIntent.BLUEPRINT, 10)),
                entities = emptyList(),
            ),
        )

        assertTrue(hits.any { it.summary.contains("Quartz") })
    }

    @Test
    fun shortItemCodeFindsM7ABlueprint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalAgentDataProvider(context)
        val query = QueryAnalyzer(provider.entityIndex()).analyze("m7a的蓝图怎么做")

        assertTrue(query.entities.any { it.type == "blueprint" && it.value == "M7A Cannon" })

        val hits = provider.search(
            query.copy(
                intents = listOf(ScoredIntent(AgentIntent.BLUEPRINT, 10)),
            ),
        )

        assertTrue(hits.any { it.summary.contains("M7A Cannon") })
    }

    @Test
    fun chineseBlueprintNameFindsTranslatedKillshotBlueprints() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalAgentDataProvider(context)
        val query = QueryAnalyzer(provider.entityIndex()).analyze("绝杀蓝图怎么做")

        assertTrue(query.entities.any { it.type == "blueprint" && it.value.contains("Killshot") })

        val hits = provider.search(
            query.copy(
                intents = listOf(ScoredIntent(AgentIntent.BLUEPRINT, 10)),
            ),
        )

        assertTrue(hits.count { it.summary.contains("绝杀") } >= 3)
        assertTrue(hits.any { it.summary.contains("铁") || it.facts.any { fact -> fact.value.contains("铁") } })
    }

    @Test
    fun chineseConstellationAliasFindsPerseusShip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalAgentDataProvider(context)
        val query = QueryAnalyzer(provider.entityIndex()).analyze("帮我搜一下英仙座")

        assertTrue(query.entities.any { it.type == "ship" && it.value == "rsi_perseus" })

        val hits = provider.search(
            query.copy(
                intents = listOf(ScoredIntent(AgentIntent.SHIP_INFO, 10)),
            ),
        )

        assertTrue(hits.any { it.summary.contains("Perseus") && it.summary.contains("英仙座") })
    }
}
