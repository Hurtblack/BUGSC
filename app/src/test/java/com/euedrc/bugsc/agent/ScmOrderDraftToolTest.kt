package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.transaction.AddressNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmOrderDraftToolTest {

    @Test
    fun createsResolvedDraftWithoutSubmittingOrder() = runBlocking {
        val state = ScmOrderDraftToolState()
        val tool = tool(state)

        val result = tool.run(
            AgentToolCall(
                tool = "draft_scm_order",
                args = mapOf("query" to "出售 绝杀步枪 2 个 单价 50000 地点 死局"),
            ),
        )

        assertEquals(null, result.error)
        assertTrue(result.summary.contains("订单草稿"))
        assertTrue(result.summary.contains("确认后会创建 SCM 挂单"))
        assertTrue(state.resolved != null)
        assertEquals(PublishCreatorType.SELL, state.resolved?.parsed?.creatorType)
        assertEquals(2, state.resolved?.parsed?.quantity)
        assertEquals("死局空间站", state.resolved?.location?.name)
    }

    @Test
    fun createsResolvedDraftFromStructuredAiArguments() = runBlocking {
        val state = ScmOrderDraftToolState()
        val tool = tool(state)

        val result = tool.run(
            AgentToolCall(
                tool = "draft_scm_order",
                args = mapOf(
                    "side" to "sell",
                    "item" to "绝杀步枪",
                    "quantity" to "2",
                    "unit_price" to "50000",
                    "location" to "死局",
                ),
            ),
        )

        assertEquals(null, result.error)
        assertTrue(result.summary.contains("订单草稿"))
        assertEquals("绝杀步枪", state.resolved?.item?.itemName)
        assertEquals(2, state.resolved?.parsed?.quantity)
        assertEquals("50000", state.resolved?.parsed?.unitPrice?.cleanText())
    }

    @Test
    fun cleansQualityFromStructuredItemName() = runBlocking {
        val state = ScmOrderDraftToolState()
        val tool = tool(state)

        val result = tool.run(
            AgentToolCall(
                tool = "draft_scm_order",
                args = mapOf(
                    "side" to "sell",
                    "item" to "绝杀品质850",
                    "unit_price" to "500000000",
                    "location" to "死局",
                ),
            ),
        )

        assertEquals(null, result.error)
        assertTrue(result.summary.contains("品质：850"))
        assertEquals("绝杀步枪", state.resolved?.item?.itemName)
        assertEquals(850, state.resolved?.parsed?.quality)
    }

    @Test
    fun storesPendingParseWhenDraftNeedsMoreInfo() = runBlocking {
        val state = ScmOrderDraftToolState()
        val tool = tool(state)

        val result = tool.run(
            AgentToolCall(
                tool = "draft_scm_order",
                args = mapOf("query" to "帮我创建一个订单"),
            ),
        )

        assertEquals(null, result.error)
        assertTrue(result.summary.contains("还缺"))
        assertTrue(state.pendingParse != null)
        assertEquals(null, state.resolved)
    }

    @Test
    fun mergesFollowUpWithPendingParse() = runBlocking {
        val state = ScmOrderDraftToolState(
            pendingParse = ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000"),
        )
        val tool = tool(state)

        val result = tool.run(
            AgentToolCall(
                tool = "draft_scm_order",
                args = mapOf("query" to "死局"),
            ),
        )

        assertTrue(result.summary.contains("订单草稿"))
        assertEquals(null, state.pendingParse)
        assertEquals("死局空间站", state.resolved?.location?.name)
    }

    private fun tool(state: ScmOrderDraftToolState): ScmOrderDraftTool =
        ScmOrderDraftTool(
            state = state,
            isLoggedIn = { true },
            itemSearch = { keyword ->
                if (keyword.contains("绝杀")) {
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
            },
            addressList = {
                listOf(
                    AddressNode(id = 7, parentId = 0, name = "死局空间站"),
                )
            },
            entityIndex = AgentEntityIndex(),
        )
}
