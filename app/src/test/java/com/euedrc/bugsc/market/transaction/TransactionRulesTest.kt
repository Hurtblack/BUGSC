package com.euedrc.bugsc.market.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TransactionRulesTest {

    @Test
    fun validatesQuantityLocationAndShippingFee() {
        val missing = TransactionRules.validate(
            quantityText = "0",
            maxQuantity = 6,
            locationId = null,
            shippingFeeText = "",
        )
        assertEquals("交易数量必须在 1 到 6 之间", missing.quantityError)
        assertEquals("请选择收货位置", missing.locationError)
        assertEquals("请输入运费", missing.shippingFeeError)
        assertFalse(missing.isValid)

        val negative = TransactionRules.validate("1", 6, 99L, "-1")
        assertEquals("运费不能为负数", negative.shippingFeeError)

        val valid = TransactionRules.validate("6", 6, 99L, "100")
        assertTrue(valid.isValid)
        assertEquals(BigDecimal("100"), valid.shippingFee)
    }

    @Test
    fun totalExcludesShippingFee() {
        assertEquals(
            BigDecimal("3000000"),
            TransactionRules.total(BigDecimal("1000000"), 3),
        )
    }

    @Test
    fun buildsAddressHierarchyAndSelectsLeaf() {
        val tree = AddressTree.build(
            listOf(
                AddressNode(1, 0, "派罗星系"),
                AddressNode(2, 1, "盛放星"),
                AddressNode(3, 2, "轨道讣闻站"),
            )
        )

        assertEquals("派罗星系", tree.roots.single().node.name)
        assertEquals("盛放星", tree.roots.single().children.single().node.name)
        assertEquals(3L, tree.roots.single().children.single().children.single().node.id)
        assertTrue(tree.isLeaf(3))
        assertFalse(tree.isLeaf(2))
    }

    @Test
    fun addressChoicesStartWithPromptAndDoNotDefaultToFirstLocation() {
        val branch = AddressBranch(
            AddressNode(30, 21, "轨道讣闻站"),
            emptyList(),
        )

        val choices = AddressChoice.withPrompt("请选择站点", listOf(branch))

        assertEquals("请选择站点", choices.first().label)
        assertEquals(null, choices.first().locationIdIfLeaf)
        assertEquals("轨道讣闻站", choices[1].label)
        assertEquals(30L, choices[1].locationIdIfLeaf)
    }

    @Test
    fun mapsTransactionFiltersToApiStatus() {
        assertEquals(null, TransactionFilter.ALL.apiStatus)
        assertEquals(1, TransactionFilter.ONGOING.apiStatus)
        assertEquals(2, TransactionFilter.COMPLETED.apiStatus)
        assertEquals(3, TransactionFilter.CANCELLED.apiStatus)
    }

    @Test
    fun evaluatesNormalAndOvernightTradeWindows() {
        val flags = listOf(true, false, false, false, false, false, false)
        assertTrue(TradeWindow.isOpen(flags, "09:00", "16:00", dayIndex = 0, minuteOfDay = 10 * 60))
        assertFalse(TradeWindow.isOpen(flags, "09:00", "16:00", dayIndex = 0, minuteOfDay = 18 * 60))

        val fridayFlags = List(7) { it == 4 }
        assertTrue(TradeWindow.isOpen(fridayFlags, "22:00", "02:00", dayIndex = 4, minuteOfDay = 23 * 60))
        assertTrue(TradeWindow.isOpen(fridayFlags, "22:00", "02:00", dayIndex = 5, minuteOfDay = 60))
    }
}
