package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.PublishCreatorType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmOrderDraftParserTest {

    @Test
    fun parsesSellOrderDraftFromChineseRequest() {
        val draft = ScmOrderDraftParser.parse("帮我创建一个出售绝杀步枪，数量 2，单价 50000 aUEC，地点新巴贝奇")

        assertTrue(draft.isOrderIntent)
        assertEquals(PublishCreatorType.SELL, draft.creatorType)
        assertEquals("绝杀步枪", draft.itemKeyword)
        assertEquals(2, draft.quantity)
        assertEquals(BigDecimal("50000"), draft.unitPrice)
        assertEquals("新巴贝奇", draft.locationKeyword)
        assertTrue(draft.isReadyForResolution)
    }

    @Test
    fun parsesBuyOrderDraftWithDefaults() {
        val draft = ScmOrderDraftParser.parse("求购 石英 3 个 单价 1000 地点 洛维尔")

        assertTrue(draft.isOrderIntent)
        assertEquals(PublishCreatorType.BUY, draft.creatorType)
        assertEquals("石英", draft.itemKeyword)
        assertEquals(3, draft.quantity)
        assertEquals(BigDecimal("1000"), draft.unitPrice)
        assertEquals("洛维尔", draft.locationKeyword)
    }

    @Test
    fun reportsMissingFieldsInsteadOfCreating() {
        val draft = ScmOrderDraftParser.parse("帮我创建一个订单")

        assertTrue(draft.isOrderIntent)
        assertFalse(draft.isReadyForResolution)
        assertTrue(draft.missingFields.contains("出售或求购"))
        assertTrue(draft.missingFields.contains("物品"))
        assertTrue(draft.missingFields.contains("单价"))
        assertTrue(draft.missingFields.contains("交易地点"))
    }

    @Test
    fun mergesFollowUpAnswerIntoPendingDraft() {
        val pending = ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000")

        val merged = ScmOrderDraftParser.mergeFollowUp(pending, "死局")

        assertTrue(merged.isOrderIntent)
        assertEquals(PublishCreatorType.SELL, merged.creatorType)
        assertEquals("绝杀步枪", merged.itemKeyword)
        assertEquals(1, merged.quantity)
        assertEquals(BigDecimal("50000"), merged.unitPrice)
        assertEquals("死局", merged.locationKeyword)
        assertTrue(merged.isReadyForResolution)
    }
}
