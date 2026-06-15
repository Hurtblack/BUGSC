package com.euedrc.bugsc.market.transaction

import java.math.BigDecimal

data class TransactionValidation(
    val quantity: Int? = null,
    val shippingFee: BigDecimal? = null,
    val quantityError: String? = null,
    val locationError: String? = null,
    val shippingFeeError: String? = null,
) {
    val isValid: Boolean
        get() = quantityError == null && locationError == null && shippingFeeError == null
}

object TransactionRules {
    fun validate(
        quantityText: String,
        maxQuantity: Int,
        locationId: Long?,
        shippingFeeText: String,
    ): TransactionValidation {
        val quantity = quantityText.trim().toIntOrNull()
        val quantityError = if (quantity == null || quantity !in 1..maxQuantity) {
            "交易数量必须在 1 到 $maxQuantity 之间"
        } else {
            null
        }
        val shippingText = shippingFeeText.trim()
        val shippingFee = shippingText.toBigDecimalOrNull()
        val shippingError = when {
            shippingText.isEmpty() -> "请输入运费"
            shippingFee == null -> "请输入有效的运费"
            shippingFee < BigDecimal.ZERO -> "运费不能为负数"
            else -> null
        }
        return TransactionValidation(
            quantity = quantity,
            shippingFee = shippingFee,
            quantityError = quantityError,
            locationError = if (locationId == null) "请选择收货位置" else null,
            shippingFeeError = shippingError,
        )
    }

    fun total(unitPrice: BigDecimal, quantity: Int): BigDecimal =
        unitPrice.multiply(BigDecimal(quantity))
}

object TradeWindow {
    fun isOpen(
        days: List<Boolean>,
        startText: String,
        endText: String,
        dayIndex: Int,
        minuteOfDay: Int,
    ): Boolean {
        if (days.size != 7 || dayIndex !in 0..6 || minuteOfDay !in 0 until MINUTES_PER_DAY) {
            return false
        }
        val start = parseMinutes(startText) ?: return false
        val end = parseMinutes(endText) ?: return false
        if (start <= end) return days[dayIndex] && minuteOfDay in start..end

        if (minuteOfDay >= start) return days[dayIndex]
        val previousIndex = (dayIndex + 6) % 7
        return minuteOfDay <= end && days[previousIndex]
    }

    private fun parseMinutes(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
