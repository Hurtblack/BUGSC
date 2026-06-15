package com.euedrc.bugsc.market.publish

import org.json.JSONObject
import java.math.BigDecimal

enum class PublishCreatorType(val apiValue: Int, val label: String) {
    BUY(0, "求购"),
    SELL(1, "出售"),
}

enum class PublishOrderStatus(val apiValue: Int, val label: String) {
    HIDDEN(0, "下架"),
    VISIBLE(1, "上架"),
}

enum class PublishExpireTime(val apiValue: Int, val label: String) {
    SEVEN_DAYS(0, "7 天"),
    FOURTEEN_DAYS(1, "14 天"),
    PERMANENT(2, "永久"),
}

data class ItemSearchResult(
    val id: Long,
    val itemName: String,
    val thumbnailUrl: String,
    val thumbnailUrlHd: String,
    val itemNameEn: String = "",
)

data class PublishItemDraft(
    val item: ItemSearchResult,
    val quantityText: String,
    val priceText: String,
    val qualityText: String = "",
)

data class PublishItemValue(
    val item: ItemSearchResult,
    val quantity: Int,
    val price: BigDecimal,
    val quality: Int?,
)

data class PublishOrderDraft(
    val creatorType: PublishCreatorType,
    val locationId: Long?,
    val status: PublishOrderStatus,
    val expireTimeType: PublishExpireTime,
    val items: List<PublishItemDraft>,
)

data class PublishItemError(
    val quantityError: String? = null,
    val priceError: String? = null,
    val qualityError: String? = null,
) {
    val isValid: Boolean get() = quantityError == null && priceError == null && qualityError == null
}

data class PublishOrderValidation(
    val locationError: String? = null,
    val itemsError: String? = null,
    val itemErrors: List<PublishItemError> = emptyList(),
    val items: List<PublishItemValue> = emptyList(),
) {
    val isValid: Boolean
        get() = locationError == null && itemsError == null && itemErrors.all(PublishItemError::isValid)
}

object PublishQuality {
    fun toJson(quality: Int?): String? =
        quality?.let {
            JSONObject()
                .put("type", "material")
                .put("quality", it)
                .toString()
        }
}

data class ItemImageStatus(
    val itemId: Long,
    val status: Int?,
    val itemName: String = "",
    val thumbnailUrl: String = "",
    val thumbnailUrlHd: String = "",
    val rejectReason: String = "",
) {
    companion object {
        fun canUpload(status: ItemImageStatus?): Boolean = status == null || status.status != 0
    }
}

fun PublishOrderDraft.validate(): PublishOrderValidation {
    val itemErrors = items.map { item ->
        val quantity = item.quantityText.trim().toIntOrNull()
        val price = item.priceText.trim().toBigDecimalOrNull()
        val qualityText = item.qualityText.trim()
        val quality = qualityText.takeIf(String::isNotBlank)?.toIntOrNull()
        PublishItemError(
            quantityError = when {
                quantity == null -> "请输入数量"
                quantity <= 0 -> "数量必须大于 0"
                else -> null
            },
            priceError = when {
                item.priceText.trim().isEmpty() -> "请输入单价"
                price == null -> "请输入有效单价"
                price <= BigDecimal.ZERO -> "单价必须大于 0"
                else -> null
            },
            qualityError = when {
                qualityText.isEmpty() -> null
                quality == null || quality !in 0..1000 -> "品质必须在 0 到 1000 之间"
                else -> null
            },
        )
    }
    val values = items.zip(itemErrors).mapNotNull { (draft, error) ->
        if (!error.isValid) return@mapNotNull null
        PublishItemValue(
            item = draft.item,
            quantity = draft.quantityText.trim().toInt(),
            price = draft.priceText.trim().toBigDecimal(),
            quality = draft.qualityText.trim().takeIf(String::isNotBlank)?.toInt(),
        )
    }
    return PublishOrderValidation(
        locationError = if (locationId == null) "请选择交易地点" else null,
        itemsError = if (items.isEmpty()) "请至少添加一个物品" else null,
        itemErrors = itemErrors,
        items = values,
    )
}
