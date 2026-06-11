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
            "unitPrice": 7000000.0
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
        assertTrue(order.isSell)
        assertEquals(7000000.0, order.unitPrice, 0.01)
        assertEquals("测试物品", order.itemName)
        assertEquals("特雷斯勒空间站", order.locationName)
        assertEquals(1, order.itemDetails.size)
    }

    @Test
    fun parsePage_emptyList() {
        val json = JSONObject("""{"code":0,"data":{"list":[],"total":0},"msg":""}""")
        val page = ScmMarketClient.parsePage(json)
        assertEquals(0, page.total)
        assertTrue(page.list.isEmpty())
    }
}
