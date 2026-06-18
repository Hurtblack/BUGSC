package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.MarketOrder
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.OwnMarketOrder
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishOrderPage
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import com.euedrc.bugsc.market.transaction.TransactionPage
import com.euedrc.bugsc.market.transaction.TransactionRecord
import com.euedrc.bugsc.scm.ScmResult
import com.euedrc.bugsc.scm.SignInSummary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ScmAgentToolsTest {

    private class FakeGateway : ScmAgentGateway {
        val requestedPaths = mutableListOf<String>()
        val statusUpdates = mutableListOf<Pair<String, PublishOrderStatus>>()
        val deletedOrders = mutableListOf<String>()
        val quantityAdds = mutableListOf<String>()
        val editedOrders = mutableListOf<EditedOrder>()

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

        override fun ownOrders(pageNo: Int, pageSize: Int, creatorType: PublishCreatorType?): PublishOrderPage =
            PublishOrderPage(
                listOf(
                    OwnMarketOrder(
                        orderNumber = "OWN-1",
                        creatorType = PublishCreatorType.SELL,
                        itemName = "科力晶",
                        remainingQuantity = 3,
                        unitPrice = BigDecimal("1200"),
                        status = PublishOrderStatus.VISIBLE,
                        createTime = "2026-06-16 12:00:00",
                    ),
                    OwnMarketOrder(
                        orderNumber = "OWN-2",
                        creatorType = PublishCreatorType.BUY,
                        itemName = "绝杀步枪",
                        remainingQuantity = 1,
                        unitPrice = BigDecimal("50000"),
                        status = PublishOrderStatus.HIDDEN,
                        createTime = "2026-06-15 12:00:00",
                    ),
                ),
                total = 2,
            )

        override fun transactionPage(pageNo: Int, pageSize: Int): TransactionPage =
            TransactionPage(
                items = listOf(
                    transactionRecord(
                        transactionNumber = "TR-BUY-1",
                        creatorType = 1,
                        orderOwnerId = 99,
                        orderOwnerName = "sellerA",
                        tradingerId = 7,
                        tradingerName = "me",
                        itemsName = "科粒晶",
                        amount = "3600",
                    ),
                    transactionRecord(
                        transactionNumber = "TR-SELL-1",
                        creatorType = 1,
                        orderOwnerId = 7,
                        orderOwnerName = "me",
                        tradingerId = 88,
                        tradingerName = "buyerB",
                        itemsName = "绝杀步枪",
                        amount = "50000",
                    ),
                ),
                total = 2,
            )

        override fun signInSummary(): SignInSummary = SignInSummary(
            totalDay = 10,
            continuousDay = 3,
            todaySignIn = false,
        )

        override fun signIn(): ScmResult = ScmResult(true, "ok", 0)

        override fun updateOwnOrder(
            orderNumber: String,
            unitPrice: BigDecimal,
            remainingQuantity: Int,
            status: PublishOrderStatus,
            expireTime: PublishExpireTime,
            quality: Int?,
        ) {
            editedOrders += EditedOrder(orderNumber, unitPrice, remainingQuantity, status, expireTime, quality)
        }

        override fun setOwnOrderStatus(orderNumber: String, status: PublishOrderStatus) {
            statusUpdates += orderNumber to status
        }

        override fun deleteOwnOrder(orderNumber: String) {
            deletedOrders += orderNumber
        }

        override fun quantityAdd(orderNumber: String) {
            quantityAdds += orderNumber
        }
    }

    private data class EditedOrder(
        val orderNumber: String,
        val unitPrice: BigDecimal,
        val remainingQuantity: Int,
        val status: PublishOrderStatus,
        val expireTime: PublishExpireTime,
        val quality: Int?,
    )

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

    @Test
    fun marketToolSummarizesSellerCountAndEachSellerPrice() = runBlocking {
        val gateway = object : ScmAgentGateway {
            override fun request(path: String): JSONObject = JSONObject("""{"code":0,"data":{"list":[]}}""")
            override fun searchItems(keyword: String): List<ItemSearchResult> = emptyList()
            override fun searchOrders(keyword: String): List<MarketOrder> = emptyList()
            override fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> =
                if (keyword == "科粒晶" && creatorType == 1) {
                    listOf(
                        marketOrder(
                            itemName = "科粒晶",
                            creatorType = 1,
                            unitPrice = 1200.0,
                            nickname = "sellerA",
                            locationName = "奥里森",
                        ),
                        marketOrder(
                            itemName = "科粒晶",
                            creatorType = 1,
                            unitPrice = 1500.0,
                            nickname = "sellerB",
                            locationName = "洛维尔",
                        ),
                    )
                } else {
                    emptyList()
                }
        }
        val tool = ScmMarketOrderSearchTool(gateway)

        val result = tool.run(AgentToolCall("search_market", mapOf("query" to "科粒晶", "side" to "sell")))

        assertTrue(result.summary.contains("当前有 2 个卖家在卖"))
        assertTrue(result.summary.contains("卖家 sellerA"))
        assertTrue(result.summary.contains("1200"))
        assertTrue(result.summary.contains("奥里森"))
        assertTrue(result.summary.contains("卖家 sellerB"))
        assertTrue(result.summary.contains("1500"))
        assertTrue(result.summary.contains("洛维尔"))
        assertTrue(result.facts.any { it.label == "卖家数量" && it.value == "2" })
    }

    @Test
    fun myOrdersToolFormatsCurrentUsersOrders() = runBlocking {
        val tool = ScmMyOrdersTool(FakeGateway(), isLoggedIn = { true })

        val result = tool.run(AgentToolCall("list_my_orders", emptyMap()))

        assertTrue(result.summary.contains("OWN-1"))
        assertTrue(result.summary.contains("科力晶"))
        assertTrue(result.summary.contains("出售"))
        assertTrue(result.summary.contains("1200"))
        assertTrue(result.summary.contains("上架"))
        assertTrue(result.summary.contains("OWN-2"))
        assertTrue(result.facts.any { it.label == "我的订单" && it.value == "OWN-1" })
    }

    @Test
    fun manageMyOrderToolHidesVisibleOwnOrder() = runBlocking {
        val gateway = FakeGateway()
        val tool = ScmManageMyOrderTool(gateway, isLoggedIn = { true })

        val result = tool.run(AgentToolCall("manage_my_order", mapOf("action" to "hide", "orderNumber" to "OWN-1")))

        assertEquals(listOf("OWN-1" to PublishOrderStatus.HIDDEN), gateway.statusUpdates)
        assertTrue(result.summary.contains("OWN-1"))
        assertTrue(result.summary.contains("下架"))
    }

    @Test
    fun manageMyOrderToolRequiresConfirmationBeforeDelete() = runBlocking {
        val gateway = FakeGateway()
        val tool = ScmManageMyOrderTool(gateway, isLoggedIn = { true })

        val result = tool.run(AgentToolCall("manage_my_order", mapOf("action" to "delete", "orderNumber" to "OWN-1")))

        assertTrue(gateway.deletedOrders.isEmpty())
        assertTrue(result.summary.contains("确认"))
        assertTrue(result.summary.contains("OWN-1"))
    }

    @Test
    fun manageMyOrderToolDeletesAfterExplicitConfirmation() = runBlocking {
        val gateway = FakeGateway()
        val tool = ScmManageMyOrderTool(gateway, isLoggedIn = { true })

        val result = tool.run(
            AgentToolCall(
                "manage_my_order",
                mapOf("action" to "delete", "orderNumber" to "OWN-1", "confirm" to "true"),
            ),
        )

        assertEquals(listOf("OWN-1"), gateway.deletedOrders)
        assertTrue(result.summary.contains("已删除"))
    }

    @Test
    fun manageMyOrderToolEditsOnlyProvidedFieldsAndKeepsCurrentValues() = runBlocking {
        val gateway = FakeGateway()
        val tool = ScmManageMyOrderTool(gateway, isLoggedIn = { true })

        val result = tool.run(
            AgentToolCall(
                "manage_my_order",
                mapOf("action" to "edit", "orderNumber" to "OWN-1", "unitPrice" to "1500"),
            ),
        )

        assertEquals(1, gateway.editedOrders.size)
        val edited = gateway.editedOrders.single()
        assertEquals("OWN-1", edited.orderNumber)
        assertEquals(BigDecimal("1500"), edited.unitPrice)
        assertEquals(3, edited.remainingQuantity)
        assertEquals(PublishOrderStatus.VISIBLE, edited.status)
        assertEquals(PublishExpireTime.SEVEN_DAYS, edited.expireTime)
        assertEquals(null, edited.quality)
        assertTrue(result.summary.contains("已更新"))
        assertTrue(result.summary.contains("7 天"))
    }

    @Test
    fun marketActivityToolIncludesOwnListingsAndBoughtTransactions() = runBlocking {
        val tool = ScmMarketActivityTool(
            gateway = FakeGateway(),
            isLoggedIn = { true },
            currentUserId = { 7L },
        )

        val result = tool.run(AgentToolCall("list_my_market_activity", emptyMap()))

        assertTrue(result.summary.contains("我的挂单"))
        assertTrue(result.summary.contains("OWN-1"))
        assertTrue(result.summary.contains("科力晶"))
        assertTrue(result.summary.contains("我的交易"))
        assertTrue(result.summary.contains("TR-BUY-1"))
        assertTrue(result.summary.contains("我买入"))
        assertTrue(result.summary.contains("卖家 sellerA"))
        assertTrue(result.summary.contains("TR-SELL-1"))
        assertTrue(result.summary.contains("我卖出"))
        assertTrue(result.summary.contains("买家 buyerB"))
        assertTrue(result.facts.any { it.label == "我的挂单数量" && it.value == "2" })
        assertTrue(result.facts.any { it.label == "我的交易数量" && it.value == "2" })
    }

    @Test
    fun signInToolRequiresScmLogin() = runBlocking {
        val tool = ScmSignInTool(FakeGateway(), isLoggedIn = { false })

        val result = tool.run(AgentToolCall("scm_sign_in", emptyMap()))

        assertTrue(result.summary.contains("需要先登录 SCM"))
        assertEquals(0.5f, result.confidence)
    }

    @Test
    fun signInToolReturnsSummaryWhenQueryingStatus() = runBlocking {
        val tool = ScmSignInTool(FakeGateway(), isLoggedIn = { true })

        val result = tool.run(AgentToolCall("scm_sign_in", mapOf("action" to "status")))

        assertTrue(result.summary.contains("连续签到 3 天"))
        assertTrue(result.summary.contains("累计 10 天"))
        assertTrue(result.summary.contains("今日未签到"))
        assertTrue(result.facts.any { it.label == "今日是否已签到" && it.value == "否" })
    }

    @Test
    fun signInToolCreatesSignInAndReturnsUpdatedSummary() = runBlocking {
        val tool = ScmSignInTool(FakeGateway(), isLoggedIn = { true })

        val result = tool.run(AgentToolCall("scm_sign_in", emptyMap()))

        assertTrue(result.summary.contains("签到成功"))
        assertTrue(result.summary.contains("连续签到 3 天"))
        assertTrue(result.facts.any { it.label == "签到结果" && it.value == "成功" })
    }

    private companion object {
        fun marketOrder(
            itemName: String,
            creatorType: Int,
            unitPrice: Double,
            nickname: String = "seller",
            locationName: String = "奥里森",
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
            nickname = nickname,
            avatar = "",
            point = 0,
            creatorStatus = 1,
            itemName = itemName,
            locationName = locationName,
            locationUrl = "",
            tradeTime = "",
            tradeStartTime = "",
            tradeEndTime = "",
            itemDetails = emptyList(),
        )

        fun transactionRecord(
            transactionNumber: String,
            creatorType: Int,
            orderOwnerId: Long,
            orderOwnerName: String,
            tradingerId: Long,
            tradingerName: String,
            itemsName: String,
            amount: String,
        ): TransactionRecord = TransactionRecord(
            transactionNumber = transactionNumber,
            orderOwnerId = orderOwnerId,
            orderOwnerName = orderOwnerName,
            orderOwnerAvatar = "",
            orderOwnerMobile = "",
            tradingerId = tradingerId,
            tradingerName = tradingerName,
            tradingerAvatar = "",
            tradingerMobile = "",
            creatorStatus = 1,
            creatorType = creatorType,
            itemsName = itemsName,
            thumbnailUrl = "",
            number = 1,
            remainingQuantity = 0,
            amount = BigDecimal(amount),
            transactionStatus = 1,
            deliveryStatus = 0,
            locationName = "奥里森",
            transactionLocationName = "新巴贝奇",
            deliveryMethod = 0,
            shippingFee = BigDecimal.ZERO,
            createTime = "2026-06-16 12:00:00",
            completionTime = "",
            cancellationTime = "",
            receiptDeadline = "",
        )
    }

}
