package com.euedrc.bugsc.market.publish

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MarketPublishModelsTest {
    @Test
    fun validatesRequiredFieldsAndItemValues() {
        val invalid = PublishOrderDraft(
            creatorType = PublishCreatorType.SELL,
            locationId = null,
            status = PublishOrderStatus.VISIBLE,
            expireTimeType = PublishExpireTime.SEVEN_DAYS,
            items = listOf(
                PublishItemDraft(
                    item = ItemSearchResult(12, "测试物品", "", ""),
                    quantityText = "0",
                    priceText = "",
                    qualityText = "1001",
                )
            ),
        ).validate()

        assertFalse(invalid.isValid)
        assertEquals("请选择交易地点", invalid.locationError)
        assertEquals("数量必须大于 0", invalid.itemErrors.single().quantityError)
        assertEquals("请输入单价", invalid.itemErrors.single().priceError)
        assertEquals("品质必须在 0 到 1000 之间", invalid.itemErrors.single().qualityError)
    }

    @Test
    fun acceptsSingleValidItem() {
        val validation = PublishOrderDraft(
            creatorType = PublishCreatorType.BUY,
            locationId = 33,
            status = PublishOrderStatus.VISIBLE,
            expireTimeType = PublishExpireTime.PERMANENT,
            items = listOf(
                PublishItemDraft(
                    item = ItemSearchResult(12, "测试物品", "", ""),
                    quantityText = "3",
                    priceText = "1500",
                    qualityText = "750",
                )
            ),
        ).validate()

        assertTrue(validation.isValid)
        assertEquals(3, validation.items.single().quantity)
        assertEquals(BigDecimal("1500"), validation.items.single().price)
        assertEquals(750, validation.items.single().quality)
    }

    @Test
    fun buildsQualityJsonOnlyWhenPresent() {
        assertNull(PublishQuality.toJson(null))

        val json = JSONObject(PublishQuality.toJson(650)!!)

        assertEquals(650, json.getInt("quality"))
    }

    @Test
    fun imageStatusAllowsUploadExceptPendingReview() {
        assertTrue(ItemImageStatus.canUpload(null))
        assertFalse(ItemImageStatus.canUpload(ItemImageStatus(itemId = 1, status = 0)))
        assertTrue(ItemImageStatus.canUpload(ItemImageStatus(itemId = 1, status = 1)))
        assertTrue(ItemImageStatus.canUpload(ItemImageStatus(itemId = 1, status = 2)))
    }
}

