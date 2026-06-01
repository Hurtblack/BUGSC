package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryCacheCodecTest {

    @Test
    fun encodesAndDecodesInventoryItems() {
        val items = listOf(
            InventoryItem(
                id = "123",
                name = "Test Ship",
                priceCents = 4500,
                currentPriceCents = 6000,
                status = "Attributed",
                date = "2026年05月22日",
                insurance = "LTI",
                contains = "Test Ship#Lifetime Insurance",
                imageUrl = "https://example.com/ship.jpg",
                page = 2,
                canGift = true,
                canReclaim = false,
                canUpgrade = true
            )
        )

        val decoded = InventoryCacheCodec.decodeItems(InventoryCacheCodec.encodeItems(items))

        assertEquals(items, decoded)
    }
}
