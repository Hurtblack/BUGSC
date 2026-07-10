package com.euedrc.bugsc.wbccu

import com.euedrc.bugsc.InventoryCcuPriceCalculator
import com.euedrc.bugsc.InventoryCcuPriceSummary
import com.euedrc.bugsc.InventoryItem
import com.euedrc.bugsc.InventoryShipPrice

data class WbccuInventoryCcuCandidate(
    val item: InventoryItem,
    val summary: InventoryCcuPriceSummary,
    val saleType: WbccuSaleType
)

object WbccuInventoryCcuMapper {

    fun candidates(
        items: List<InventoryItem>,
        shipPrices: List<InventoryShipPrice>
    ): List<WbccuInventoryCcuCandidate> {
        return items.mapNotNull { item ->
            val ccuInfo = item.ccuInfo ?: return@mapNotNull null
            val summary = InventoryCcuPriceCalculator.summarize(item, ccuInfo, shipPrices)
                ?: return@mapNotNull null
            WbccuInventoryCcuCandidate(
                item = item,
                summary = summary,
                saleType = saleTypeFromItem(item)
            )
        }.sortedWith(
            compareByDescending<WbccuInventoryCcuCandidate> { it.saleType == WbccuSaleType.WARBOND }
                .thenBy { it.summary.fromShipPriceCents }
                .thenBy { it.summary.toShipPriceCents }
        )
    }

    fun saleTypeFromItem(item: InventoryItem): WbccuSaleType {
        val text = "${item.name} ${item.contains}"
        return when {
            text.contains("Warbond", ignoreCase = true) -> WbccuSaleType.WARBOND
            text.contains("Standard", ignoreCase = true) -> WbccuSaleType.STANDARD
            else -> WbccuSaleType.UNKNOWN
        }
    }
}
