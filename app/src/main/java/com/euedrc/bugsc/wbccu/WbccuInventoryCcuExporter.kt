package com.euedrc.bugsc.wbccu

import java.util.Locale

object WbccuInventoryCcuExporter {

    fun exportText(candidates: List<WbccuInventoryCcuCandidate>): String {
        val wbCount = candidates.count { it.saleType == WbccuSaleType.WARBOND }
        return buildString {
            appendLine("库存 CCU ${candidates.size} 张，其中 WB ${wbCount} 张")
            candidates.forEachIndexed { index, candidate ->
                val summary = candidate.summary
                append(index + 1)
                append(". ")
                append(summary.fromShipName)
                append(" -> ")
                append(summary.toShipName)
                append(" | ")
                append(saleLabel(candidate.saleType))
                append(" | 实付 ")
                append(money(candidate.item.priceCents))
                append(" | 船价 ")
                append(money(summary.fromShipPriceCents))
                append(" -> ")
                append(money(summary.toShipPriceCents))
                append(" | 标准差价 ")
                append(money(summary.standardUpgradeValueCents))
                append(" | 省 ")
                append(saving(summary.savingCents))
                append(" | 库存 ")
                append(candidate.item.name)
                if (candidate.item.id.isNotBlank()) {
                    append(" | id ")
                    append(candidate.item.id)
                }
                appendLine()
            }
        }.trimEnd()
    }

    private fun saleLabel(type: WbccuSaleType): String {
        return when (type) {
            WbccuSaleType.BASE -> "底"
            WbccuSaleType.WARBOND -> "WB"
            WbccuSaleType.STANDARD -> "标准"
            WbccuSaleType.UNKNOWN -> "未知"
        }
    }

    private fun money(cents: Int): String {
        if (cents <= 0) return "-"
        val value = cents / 100.0
        return if (cents % 100 == 0) {
            "$${value.toInt()}"
        } else {
            "$${"%.2f".format(Locale.US, value)}"
        }
    }

    private fun saving(cents: Int): String {
        return when {
            cents > 0 -> money(cents)
            cents == 0 -> "-"
            else -> "-${money(-cents)}"
        }
    }
}
