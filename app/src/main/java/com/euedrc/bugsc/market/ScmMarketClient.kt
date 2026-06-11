package com.euedrc.bugsc.market

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ScmMarketClient {

    fun fetchPage(creatorType: Int, pageNo: Int, pageSize: Int = 10, keyword: String = ""): MarketPage {
        val sb = StringBuilder(BASE_URL)
            .append("/sc/orders/page?pageNo=").append(pageNo)
            .append("&pageSize=").append(pageSize)
            .append("&creatorType=").append(creatorType)
        if (keyword.isNotBlank()) {
            sb.append("&itemName=").append(URLEncoder.encode(keyword, "UTF-8"))
        }
        return parsePage(JSONObject(httpGet(sb.toString())))
    }

    fun fetchDetail(orderNumber: String): MarketOrder {
        val url = "$BASE_URL/sc/orders/get?orderNumber=$orderNumber"
        val json = JSONObject(httpGet(url))
        return parseOrder(json.getJSONObject("data"))
    }

    companion object {
        private const val BASE_URL = "https://flowcld.xyz/app-api"
        private const val USER_AGENT = "SCMobiGlas-Android-App"

        fun parsePage(json: JSONObject): MarketPage {
            val data = json.getJSONObject("data")
            val arr = data.getJSONArray("list")
            val list = (0 until arr.length()).map { parseOrder(arr.getJSONObject(it)) }
            return MarketPage(list, data.optInt("total", 0))
        }

        private fun parseOrder(obj: JSONObject): MarketOrder {
            val detailsArr = obj.optJSONArray("itemDetails")
            val details = if (detailsArr != null) {
                (0 until detailsArr.length()).map { i ->
                    val d = detailsArr.getJSONObject(i)
                    MarketOrderItem(
                        itemId = d.optString("itemId"),
                        itemName = d.optString("itemName"),
                        thumbnailUrl = d.optString("thumbnailUrl"),
                        thumbnailUrlHd = d.optString("thumbnailUrlHd"),
                        quantity = d.optInt("quantity", 1),
                        unitPrice = d.optDouble("unitPrice", 0.0),
                    )
                }
            } else emptyList()

            return MarketOrder(
                orderNumber = obj.optString("orderNumber"),
                creatorType = obj.optInt("creatorType"),
                remainingQuantity = obj.optInt("remainingQuantity"),
                unitPrice = obj.optDouble("unitPrice", 0.0),
                status = obj.optInt("status"),
                remark = obj.optString("remark", ""),
                expireTime = obj.optLong("expireTime"),
                createTime = obj.optLong("createTime"),
                nickname = obj.optString("nickname"),
                avatar = obj.optString("avatar"),
                point = obj.optInt("point"),
                itemName = obj.optString("itemName"),
                locationName = obj.optString("locationName"),
                tradeTime = obj.optString("tradeTime"),
                tradeStartTime = obj.optString("tradeStartTime"),
                tradeEndTime = obj.optString("tradeEndTime"),
                itemDetails = details,
            )
        }

        private fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("tenant-id", "1")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("SCM 市场 API 返回 ${conn.responseCode}")
                }
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
