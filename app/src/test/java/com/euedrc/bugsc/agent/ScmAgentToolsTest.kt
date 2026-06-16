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
        val searchedOrders = mutableListOf<Pair<String, Int?>>()

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

        override fun searchOrders(keyword: String): List<MarketOrder> {
            searchedOrders += keyword to null
            return emptyList()
        }

        override fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> {
            searchedOrders += keyword to creatorType
            return if (keyword.isBlank() && creatorType == 1) {
                listOf(marketOrder(itemName = "科力晶", creatorType = 1, unitPrice = 1200.0))
            } else {
                emptyList()
            }
        }
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

    @Test
    fun marketToolUsesSellSideWhenUserWantsToBuyCurrentPrice() = runBlocking {
        val gateway = KeywordSensitiveGateway()
        val tool = ScmMarketOrderSearchTool(gateway)

        tool.run(AgentToolCall("search_market", mapOf("query" to "科粒晶", "side" to "sell")))

        assertTrue(gateway.searchedOrders.contains("科粒晶" to 1))
    }

    @Test
    fun marketToolNoResultMessageIncludesOriginalQuery() = runBlocking {
        val gateway = object : ScmAgentGateway {
            override fun request(path: String): JSONObject = JSONObject("""{"code":0,"data":{"list":[]}}""")
            override fun searchItems(keyword: String): List<ItemSearchResult> = emptyList()
            override fun searchOrders(keyword: String): List<MarketOrder> = emptyList()
            override fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> = emptyList()
        }
        val tool = ScmMarketOrderSearchTool(gateway)

        val result = tool.run(AgentToolCall("search_market", mapOf("query" to "科粒晶", "side" to "sell")))

        assertTrue(result.summary.contains("科粒晶"))
        assertTrue(result.summary.contains("没有查到"))
    }

    @Test
    fun marketToolCanListCurrentSellOrdersWhenQueryIsBlank() = runBlocking {
        val gateway = KeywordSensitiveGateway()
        val tool = ScmMarketOrderSearchTool(gateway)

        val result = tool.run(AgentToolCall("search_market", mapOf("query" to "", "side" to "sell")))

        assertTrue(gateway.searchedOrders.contains("" to 1))
        assertTrue(result.summary.contains("科力晶"))
        assertTrue(result.summary.contains("出售"))
        assertTrue(result.summary.contains("1200"))
    }

    @Test
    fun marketToolIncludesSellerInSummaryAndFacts() = runBlocking {
        val gateway = KeywordSensitiveGateway()
        val tool = ScmMarketOrderSearchTool(gateway)

        val result = tool.run(AgentToolCall("search_market", mapOf("query" to "", "side" to "sell")))

        assertTrue(result.summary.contains("卖家 seller"))
        assertTrue(result.facts.any { it.label == "卖家" && it.value == "seller" })
    }

    private companion object {
        fun marketOrder(
            itemName: String,
            creatorType: Int,
            unitPrice: Double,
        ): MarketOrder = MarketOrder(
            orderNumber = "ORD-1",
            creatorId = 1L,
            creatorType = creatorType,
            remainingQuantity = 10,
            unitPrice = unitPrice,
            status = 1,
            remark = "",
            expireTime = 0L,
            createTime = 0L,
            nickname = "seller",
            avatar = "",
            point = 0,
            creatorStatus = 1,
            itemName = itemName,
            locationName = "奥里森",
            locationUrl = "",
            tradeTime = "",
            tradeStartTime = "",
            tradeEndTime = "",
            itemDetails = emptyList(),
        )
    }

}
