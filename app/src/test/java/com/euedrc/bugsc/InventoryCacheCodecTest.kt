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
                canUpgrade = true,
                subItems = listOf(
                    InventorySubItem(
                        title = "Test Ship",
                        kind = "Ship",
                        subtitle = "Flight ready",
                        imageUrl = "https://example.com/sub.jpg"
                    )
                ),
                upgradeData = """{"fromShip":{"name":"A"},"toShip":{"name":"B"}}""",
                ccuInfo = InventoryCcuInfo(fromShipName = "A", toShipName = "B", fromShipId = 1, toShipId = 2)
            )
        )

        val decoded = InventoryCacheCodec.decodeItems(InventoryCacheCodec.encodeItems(items))

        assertEquals(items, decoded)
    }
}
