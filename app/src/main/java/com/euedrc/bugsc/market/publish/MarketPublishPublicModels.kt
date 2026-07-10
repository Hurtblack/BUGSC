package com.euedrc.bugsc.market.publish

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class OwnMarketOrder(
    val orderNumber: String,
    val creatorType: PublishCreatorType,
    val itemName: String,
    val remainingQuantity: Int,
    val unitPrice: BigDecimal,
    val status: PublishOrderStatus,
    val createTime: String,
)

data class PublishOrderPage(val items: List<OwnMarketOrder>, val total: Long)
data class UploadResult(val message: String)

object MarketPublishJson {
    fun orderBody(
        creatorType: PublishCreatorType,
        locationId: Long,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        items: List<PublishItemValue>,
        manifestId: Long?,
    ): String {
        val json = JSONObject()
            .put("creatorType", creatorType.apiValue)
            .put("locationId", locationId)
            .put("remainingQuantity", items.sumOf { it.quantity })
            .put("unitPrice", items.firstOrNull()?.price ?: BigDecimal.ZERO)
            .put("displayType", if (manifestId == null) DISPLAY_SINGLE_ITEM else DISPLAY_ITEM_LIST)
            .put("status", status.apiValue)
            .put("items", orderItems(items))
            .put("expireTimeType", expireTime.apiValue)
        manifestId?.let { json.put("manifestId", it) }
        return json.toString()
    }

    fun itemListBody(title: String, items: List<PublishItemValue>): String =
        JSONObject()
            .put("title", title.take(50))
            .put("items", listItems(items))
            .toString()

    fun updateBody(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    ): String {
        val json = JSONObject()
            .put("orderNumber", orderNumber)
            .put("unitPrice", unitPrice)
            .put("remainingQuantity", remainingQuantity)
            .put("status", status.apiValue)
            .put("expireTimeType", expireTime.apiValue)
        PublishQuality.toJson(quality)?.let { json.put("qualityData", it) }
        return json.toString()
    }

    fun findCreatedOrderNumber(page: PublishOrderPage, creatorType: PublishCreatorType, itemName: String): String =
        page.items.firstOrNull { it.creatorType == creatorType && it.itemName == itemName }?.orderNumber.orEmpty()

    private fun orderItems(items: List<PublishItemValue>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            val obj = JSONObject()
                .put("itemId", item.item.id)
                .put("quantity", item.quantity)
                .put("price", item.price)
            PublishQuality.toJson(item.quality)?.let { obj.put("qualityData", it) }
            put(obj)
        }
    }

    private fun listItems(items: List<PublishItemValue>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            val obj = JSONObject()
                .put("itemId", item.item.id)
                .put("quantity", item.quantity)
                .put("unitPrice", item.price)
            PublishQuality.toJson(item.quality)?.let { obj.put("qualityData", it) }
            put(obj)
        }
    }

    private const val DISPLAY_SINGLE_ITEM = 1
    private const val DISPLAY_ITEM_LIST = 2
}
