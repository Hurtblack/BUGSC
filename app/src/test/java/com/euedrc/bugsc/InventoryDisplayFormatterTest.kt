package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryDisplayFormatterTest {

    @Test
    fun translatesWarbondCcuRouteAndTags() {
        val display = InventoryDisplayFormatter.format(
            item(
                name = "Upgrade - Terrapin to Railen Warbond Edition",
                priceCents = 500,
                canUpgrade = false
            ),
            shipAliases = mapOf("Terrapin" to "陆龟", "Railen" to "瑞伦")
        )

        assertEquals("陆龟 -> 瑞伦", display.title)
        assertEquals("Terrapin -> Railen", display.subtitle)
        assertTrue(display.tags.contains("CCU"))
        assertTrue(display.tags.contains("WB"))
        assertTrue(display.tags.contains("价格 $5.00"))
    }

    @Test
    fun translatesCcuShortShipNameWithAliasFallback() {
        val display = InventoryDisplayFormatter.format(
            item(
                name = "Upgrade - Paladin to C2 Hercules Warbond Edition",
                priceCents = 1000
            ),
            shipAliases = mapOf("C2 Hercules Starlifter" to "大力神 C2", "Paladin" to "圣骑士")
        )

        assertEquals("圣骑士 -> 大力神 C2", display.title)
    }

    @Test
    fun classifiesPaintItems() {
        val display = InventoryDisplayFormatter.format(
            item(name = "Railen Paint Pack", priceCents = 1100),
            shipAliases = mapOf("Railen" to "瑞伦")
        )

        assertEquals("Railen Paint Pack", display.title)
        assertTrue(display.tags.contains("皮肤"))
        assertTrue(display.tags.contains("价格 $11.00"))
    }

    @Test
    fun classifiesStandaloneShipsAndShowsTranslatedContents() {
        val display = InventoryDisplayFormatter.format(
            item(
                name = "Standalone Ship - Railen Warbond Edition",
                priceCents = 36000,
                contains = "Railen#Lifetime Insurance",
                canUpgrade = true
            ),
            shipAliases = mapOf("Railen" to "瑞伦")
        )

        assertEquals("瑞伦", display.title)
        assertEquals("Standalone Ship - Railen Warbond Edition", display.subtitle)
        assertTrue(display.tags.contains("整船/包"))
        assertTrue(display.tags.contains("WB"))
    }

    private fun item(
        name: String,
        priceCents: Int = 0,
        contains: String = "",
        canUpgrade: Boolean = false
    ): InventoryItem = InventoryItem(
        id = "1",
        name = name,
        priceCents = priceCents,
        currentPriceCents = priceCents,
        status = "Attributed",
        date = "2026年06月18日",
        insurance = "",
        contains = contains,
        imageUrl = "",
        page = 1,
        canGift = false,
        canReclaim = true,
        canUpgrade = canUpgrade
    )
}
