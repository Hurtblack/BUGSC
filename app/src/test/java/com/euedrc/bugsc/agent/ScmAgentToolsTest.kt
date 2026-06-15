package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.MarketOrder
import com.euedrc.bugsc.market.publish.ItemSearchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmAgentToolsTest {

    private class FakeGateway : ScmAgentGateway {
        val requestedPaths = mutableListOf<String>()

        override fun request(path: String): JSONObject {
            requestedPaths += path
            return JSONObject(
                """
                {
                  "code": 0,
                  "data": {
                    "list": [
                      {
                        "id": 7,
                        "blueprintName": "Killshot Rifle",
                        "blueprintNameCn": "绝杀步枪",
                        "categoryName": "FPSWeapons",
                        "craftTimeSeconds": 120,
                        "rewardMissionCount": 3
                      }
                    ],
                    "total": 1
                  }
                }
                """.trimIndent(),
            )
        }

        override fun searchItems(keyword: String): List<ItemSearchResult> = listOf(
            ItemSearchResult(
                id = 42,
                itemName = "绝杀步枪",
                itemNameEn = "Killshot Rifle",
                thumbnailUrl = "",
                thumbnailUrlHd = "",
            ),
        )

        override fun searchOrders(keyword: String): List<MarketOrder> = emptyList()
    }

    private class KeywordSensitiveGateway : ScmAgentGateway {
        val searchedItems = mutableListOf<String>()

        override fun request(path: String): JSONObject = JSONObject("""{"code":0,"data":{"list":[]}}""")

        override fun searchItems(keyword: String): List<ItemSearchResult> {
            searchedItems += keyword
            return if (keyword == "Killshot Rifle") {
                listOf(
                    ItemSearchResult(
                        id = 42,
                        itemName = "绝杀步枪",
                        itemNameEn = "Killshot Rifle",
                        thumbnailUrl = "",
                        thumbnailUrlHd = "",
                    ),
                )
            } else {
                emptyList()
            }
        }

        override fun searchOrders(keyword: String): List<MarketOrder> = emptyList()
    }

    @Test
    fun scmBlueprintToolFormatsChineseFirstFacts() = runBlocking {
        val tool = ScmBlueprintSearchTool(FakeGateway())

        val result = tool.run(AgentToolCall("search_scm_blueprint", mapOf("term" to "绝杀")))

        assertTrue(result.summary.contains("绝杀步枪 / Killshot Rifle"))
        assertTrue(result.facts.any { it.label == "蓝图" && it.value.contains("绝杀步枪") })
        assertTrue(result.facts.any { it.label == "来源任务数" && it.value == "3" })
        assertEquals(0.78f, result.confidence)
    }

    @Test
    fun scmItemToolFormatsItemIdForOrderDraftResolution() = runBlocking {
        val tool = ScmItemSearchTool(FakeGateway())

        val result = tool.run(AgentToolCall("search_scm_item", mapOf("term" to "绝杀")))

        assertTrue(result.summary.contains("绝杀步枪 / Killshot Rifle"))
        assertTrue(result.facts.any { it.label == "物品ID" && it.value == "42" })
        assertEquals(0.76f, result.confidence)
    }

    @Test
    fun scmItemToolExpandsShortChineseAliasBeforeRemoteSearch() = runBlocking {
        val gateway = KeywordSensitiveGateway()
        val tool = ScmItemSearchTool(
            gateway = gateway,
            entityIndex = AgentEntityIndex(
                listOf(
                    AgentEntity(
                        type = "wikelo_material",
                        value = "Killshot Rifle",
                        displayName = "绝杀步枪",
                        aliases = listOf("绝杀步枪", "Killshot Rifle"),
                    ),
                ),
            ),
        )

        val result = tool.run(AgentToolCall("search_scm_item", mapOf("term" to "绝杀")))

        assertTrue(gateway.searchedItems.contains("绝杀"))
        assertTrue(gateway.searchedItems.contains("Killshot Rifle"))
        assertTrue(result.summary.contains("绝杀步枪 / Killshot Rifle"))
    }

    @Test
    fun scmItemToolCleansNaturalSearchPhraseBeforeExpansion() = runBlocking {
        val gateway = KeywordSensitiveGateway()
        val tool = ScmItemSearchTool(
            gateway = gateway,
            entityIndex = AgentEntityIndex(
                listOf(
                    AgentEntity(
                        type = "wikelo_material",
                        value = "Killshot Rifle",
                        displayName = "绝杀步枪",
                        aliases = listOf("绝杀步枪", "Killshot Rifle"),
                    ),
                ),
            ),
        )

        val result = tool.run(AgentToolCall("search_scm_item", mapOf("term" to "就叫绝杀你搜一下scm")))

        assertTrue(gateway.searchedItems.contains("绝杀"))
        assertTrue(gateway.searchedItems.contains("Killshot Rifle"))
        assertTrue(result.summary.contains("Killshot Rifle"))
    }
}
