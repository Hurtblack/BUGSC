package com.euedrc.bugsc.market

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScmMarketClientTest {

    private val samplePageJson = """
    {
      "code": 0,
      "data": {
        "list": [{
          "orderNumber": "CSDD202606101934511",
          "creatorType": 1,
          "remainingQuantity": 3,
          "unitPrice": 7000000.0,
          "status": 1,
          "remark": "行政机库套卡",
          "expireTime": 1781625600000,
          "createTime": 1781091291000,
          "creatorId": 555,
          "nickname": "TestUser",
          "avatar": "https://example.com/avatar.png",
          "point": 10,
          "displayType": 1,
          "itemName": "测试物品",
          "locationId": 16,
          "locationName": "特雷斯勒空间站",
          "tradeTime": "[1, 1, 1, 1, 1, 1, 1]",
          "tradeStartTime": "20:00",
          "tradeEndTime": "23:00",
          "itemDetails": [{
            "itemId": "49038",
            "itemName": "测试物品",
            "thumbnailUrl": "https://example.com/thumb.png",
            "thumbnailUrlHd": "https://example.com/hd.png",
            "quantity": 1,
            "unitPrice": 7000000.0,
            "qualityData": "{\"type\":\"material\",\"quality\":850}"
          }]
        }],
        "total": 1
      },
      "msg": ""
    }
    """.trimIndent()

    @Test
    fun parsePage_extractsOrderCorrectly() {
        val page = ScmMarketClient.parsePage(JSONObject(samplePageJson))
        assertEquals(1, page.total)
        assertEquals(1, page.list.size)
        val order = page.list[0]
        assertEquals("CSDD202606101934511", order.orderNumber)
        assertEquals(555L, order.creatorId)
        assertTrue(order.isSell)
        assertEquals(7000000.0, order.unitPrice, 0.01)
        assertEquals("测试物品", order.itemName)
        assertEquals("特雷斯勒空间站", order.locationName)
        assertEquals(1, order.itemDetails.size)
        assertEquals(850, order.itemDetails.single().quality)
    }

    @Test
    fun parsePage_extractsQualityFromObjectQualityData() {
        val json = JSONObject(
            """
            {
              "code": 0,
              "data": {
                "list": [{
                  "orderNumber": "Q1",
                  "creatorType": 1,
                  "itemName": "品质物品",
                  "itemDetails": [{
                    "itemId": "1",
                    "itemName": "品质物品",
                    "quantity": 1,
                    "unitPrice": 1000,
                    "qualityData": {"type":"material","quality":650}
                  }]
                }],
                "total": 1
              }
            }
            """.trimIndent(),
        )

        val item = ScmMarketClient.parsePage(json).list.single().itemDetails.single()

        assertEquals(650, item.quality)
    }

    @Test
    fun parsePage_usesPublicMaskedNicknameAsReturnedByBackend() {
        val json = JSONObject(
            """
            {
              "code": 0,
              "data": {
                "list": [{
                  "orderNumber": "N1",
                  "creatorType": 1,
                  "creatorId": 300,
                  "nickname": "U******e",
                  "itemName": "测试物品",
                  "itemDetails": []
                }],
                "total": 1
              }
            }
            """.trimIndent(),
        )

        val order = ScmMarketClient.parsePage(json).list.single()

        assertEquals("U******e", order.nickname)
    }

    @Test
    fun parsePage_emptyList() {
        val json = JSONObject("""{"code":0,"data":{"list":[],"total":0},"msg":""}""")
        val page = ScmMarketClient.parsePage(json)
        assertEquals(0, page.total)
        assertTrue(page.list.isEmpty())
    }
}
