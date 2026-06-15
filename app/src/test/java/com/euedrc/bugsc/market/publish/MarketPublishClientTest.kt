package com.euedrc.bugsc.market.publish

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MarketPublishClientTest {
    @Test
    fun buildsSingleItemOrderBody() {
        val body = MarketPublishJson.orderBody(
            creatorType = PublishCreatorType.SELL,
            locationId = 88,
            status = PublishOrderStatus.VISIBLE,
            expireTime = PublishExpireTime.SEVEN_DAYS,
            items = listOf(itemValue(id = 11, quantity = 2, price = "3000", quality = 600)),
            manifestId = null,
        )
        val json = JSONObject(body)

        assertEquals(1, json.getInt("creatorType"))
        assertEquals(88L, json.getLong("locationId"))
        assertEquals(2, json.getInt("remainingQuantity"))
        assertEquals(1, json.getInt("displayType"))
        assertEquals("3000", json.get("unitPrice").toString())
        assertEquals(1, json.getInt("status"))
        assertEquals(0, json.getInt("expireTimeType"))
        assertNull(json.opt("manifestId"))
        val item = json.getJSONArray("items").getJSONObject(0)
        assertEquals(11L, item.getLong("itemId"))
        assertEquals(2, item.getInt("quantity"))
        assertEquals("3000", item.get("price").toString())
        val quality = JSONObject(item.getString("qualityData"))
        assertEquals("material", quality.getString("type"))
        assertEquals(600, quality.getInt("quality"))
    }

    @Test
    fun buildsMultiItemListBody() {
        val body = MarketPublishJson.itemListBody(
            title = "测试清单",
            items = listOf(
                itemValue(id = 11, quantity = 2, price = "3000", quality = null),
                itemValue(id = 12, quantity = 1, price = "5000", quality = 900),
            ),
        )
        val json = JSONObject(body)

        assertEquals("测试清单", json.getString("title"))
        assertEquals(2, json.getJSONArray("items").length())
        assertEquals("5000", json.getJSONArray("items").getJSONObject(1).get("unitPrice").toString())
        val quality = JSONObject(json.getJSONArray("items").getJSONObject(1).getString("qualityData"))
        assertEquals("material", quality.getString("type"))
        assertEquals(900, quality.getInt("quality"))
    }

    @Test
    fun buildsMultiItemOrderBodyAsChecklistDisplayType() {
        val body = MarketPublishJson.orderBody(
            creatorType = PublishCreatorType.BUY,
            locationId = 88,
            status = PublishOrderStatus.VISIBLE,
            expireTime = PublishExpireTime.FOURTEEN_DAYS,
            items = listOf(
                itemValue(id = 11, quantity = 2, price = "3000", quality = null),
                itemValue(id = 12, quantity = 1, price = "5000", quality = null),
            ),
            manifestId = 99,
        )
        val json = JSONObject(body)

        assertEquals(2, json.getInt("displayType"))
        assertEquals(99L, json.getLong("manifestId"))
        assertEquals("3000", json.get("unitPrice").toString())
    }

    @Test
    fun picksFirstMatchingCreatedOrderNumber() {
        val page = PublishOrderPage(
            listOf(
                OwnMarketOrder("OLD", PublishCreatorType.SELL, "旧物品", 1, BigDecimal("1"), PublishOrderStatus.VISIBLE, ""),
                OwnMarketOrder("NEW", PublishCreatorType.SELL, "测试物品", 2, BigDecimal("3000"), PublishOrderStatus.VISIBLE, ""),
            ),
            total = 2,
        )

        assertEquals("NEW", MarketPublishJson.findCreatedOrderNumber(page, PublishCreatorType.SELL, "测试物品"))
    }

    private fun itemValue(id: Long, quantity: Int, price: String, quality: Int?) = PublishItemValue(
        item = ItemSearchResult(id, if (id == 11L) "测试物品" else "配件", "", ""),
        quantity = quantity,
        price = BigDecimal(price),
        quality = quality,
    )
}
