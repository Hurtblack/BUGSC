package com.euedrc.bugsc.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryAnalyzerTest {

    private val analyzer = QueryAnalyzer(
        AgentEntityIndex(
            entries = listOf(
                AgentEntity("ship", "f7a", "F7A Hornet Mk II", listOf("f7a", "大黄蜂")),
                AgentEntity("mineral", "quantanium", "Quantanium", listOf("量子矿", "quantainium")),
                AgentEntity("blueprint", "zeus-blueprint", "Zeus Blueprint", listOf("宙斯蓝图", "蓝图")),
            ),
        ),
    )

    @Test
    fun miningQuestionMatchesMiningIntentAndMineralEntity() {
        val query = analyzer.analyze("量子矿哪里采？")

        assertEquals(AgentIntent.MINING, query.intents.first().intent)
        assertTrue(query.entities.any { it.type == "mineral" && it.value == "quantanium" })
    }

    @Test
    fun shipQuestionMatchesShipIntentAndShipEntity() {
        val query = analyzer.analyze("F7A 火力怎么样")

        assertEquals(AgentIntent.SHIP_INFO, query.intents.first().intent)
        assertTrue(query.entities.any { it.type == "ship" && it.value == "f7a" })
    }

    @Test
    fun blueprintSourceQuestionKeepsBlueprintAndMissionIntents() {
        val query = analyzer.analyze("这个蓝图材料在哪个任务拿")
        val intents = query.intents.map { it.intent }

        assertTrue(intents.contains(AgentIntent.BLUEPRINT))
        assertTrue(intents.contains(AgentIntent.MISSION))
    }

    @Test
    fun identityAndPrivacyQuestionMatchesSelfHelpFirst() {
        val query = analyzer.analyze("你是谁？我的 Key 会上传吗？")

        assertEquals(AgentIntent.SELF_HELP, query.intents.first().intent)
    }
}
