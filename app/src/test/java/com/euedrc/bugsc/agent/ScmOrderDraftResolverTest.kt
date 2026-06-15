package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.transaction.AddressNode
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmOrderDraftResolverTest {

    @Test
    fun resolvesItemAndLocationForReadyDraft() {
        val resolver = ScmOrderDraftResolver(
            itemSearch = {
                listOf(ItemSearchResult(42, "绝杀步枪", "", "", "Killshot Rifle"))
            },
            addressList = {
                listOf(AddressNode(9, 0, "新巴贝奇"))
            },
        )
        val parsed = ScmOrderDraftParseResult(
            isOrderIntent = true,
            creatorType = PublishCreatorType.SELL,
            itemKeyword = "绝杀",
            quantity = 2,
            unitPrice = BigDecimal("50000"),
            locationKeyword = "新巴贝奇",
        )

        val result = resolver.resolve(parsed)

        assertTrue(result is ScmOrderDraftResolution.Resolved)
        val resolved = result as ScmOrderDraftResolution.Resolved
        assertEquals(42, resolved.item.id)
        assertEquals(9, resolved.location.id)
        assertTrue(resolved.confirmationMarkdown().contains("绝杀步枪 / Killshot Rifle"))
        assertTrue(resolved.confirmationMarkdown().contains("50000 aUEC"))
    }

    @Test
    fun returnsMissingWhenParsedDraftIsIncomplete() {
        val resolver = ScmOrderDraftResolver(itemSearch = { emptyList() }, addressList = { emptyList() })

        val result = resolver.resolve(ScmOrderDraftParser.parse("帮我创建一个订单"))

        assertTrue(result is ScmOrderDraftResolution.NeedMoreInfo)
        assertTrue((result as ScmOrderDraftResolution.NeedMoreInfo).message.contains("还缺"))
    }

    @Test
    fun returnsNeedMoreInfoWhenItemCannotBeResolved() {
        val resolver = ScmOrderDraftResolver(
            itemSearch = { emptyList() },
            addressList = { listOf(AddressNode(9, 0, "新巴贝奇")) },
        )

        val result = resolver.resolve(ScmOrderDraftParser.parse("出售 绝杀 1 个 单价 50000 地点 新巴贝奇"))

        assertTrue(result is ScmOrderDraftResolution.NeedMoreInfo)
        assertTrue((result as ScmOrderDraftResolution.NeedMoreInfo).message.contains("没有找到物品"))
    }

    @Test
    fun fuzzyMatchesShortLocationAlias() {
        val resolver = ScmOrderDraftResolver(
            itemSearch = {
                listOf(ItemSearchResult(42, "绝杀步枪", "", "", "Killshot Rifle"))
            },
            addressList = {
                listOf(AddressNode(99, 0, "死局空间站"))
            },
        )

        val result = resolver.resolve(ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000 地点 死局"))

        assertTrue(result is ScmOrderDraftResolution.Resolved)
        assertEquals("死局空间站", (result as ScmOrderDraftResolution.Resolved).location.name)
    }
}
