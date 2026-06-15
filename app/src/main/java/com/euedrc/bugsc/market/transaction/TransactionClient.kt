package com.euedrc.bugsc.market.transaction

import com.euedrc.bugsc.scm.ScmAuthStore
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder

object TransactionParser {
    // 创建接口必须返回可用于详情查询的 transactionNumber；数据库 id 不能当作交易编号展示。
    fun parseCreate(json: JSONObject): TransactionCreateResult {
        requireSuccess(json)
        val transactionNumber = when (val data = json.opt("data")) {
            is String -> data
            is JSONObject -> data.optString("transactionNumber")
            else -> ""
        }.trim()
        if (transactionNumber.isBlank() || transactionNumber.all(Char::isDigit)) {
            throw TransactionContractException("后台未返回交易编号，请前往“我的交易”查看")
        }
        return TransactionCreateResult(transactionNumber)
    }

    fun parseBoolean(json: JSONObject): Boolean {
        requireSuccess(json)
        return json.optBoolean("data", false)
    }

    fun parseAddresses(json: JSONObject): List<AddressNode> {
        requireSuccess(json)
        val array = json.optJSONArray("data") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                AddressNode(
                    id = it.optLong("id"),
                    parentId = it.optLong("parentId"),
                    name = it.string("name"),
                )
            }
        }
    }

    fun parsePage(json: JSONObject): TransactionPage {
        requireSuccess(json)
        val data = json.optJSONObject("data") ?: return TransactionPage(emptyList(), 0)
        val array = data.optJSONArray("list")
        val items = if (array == null) emptyList() else (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.let(::parseRecord)
        }
        return TransactionPage(items, data.optLong("total"))
    }

    fun parseDetail(json: JSONObject): TransactionRecord {
        requireSuccess(json)
        return parseRecord(json.getJSONObject("data"))
    }

    fun parseVoid(json: JSONObject) {
        requireSuccess(json)
    }

    private fun parseRecord(data: JSONObject): TransactionRecord = TransactionRecord(
        transactionNumber = data.string("transactionNumber"),
        orderOwnerId = data.optLong("orderOwnerId"),
        orderOwnerName = data.string("orderOwnerName"),
        orderOwnerAvatar = data.string("orderOwnerAvatar"),
        orderOwnerMobile = data.string("orderOwnerMobile"),
        tradingerId = data.optLong("tradingerId"),
        tradingerName = data.string("tradingerName"),
        tradingerAvatar = data.string("tradingerAvatar"),
        tradingerMobile = data.string("tradingerMobile"),
        creatorStatus = data.optInt("creatorStatus"),
        creatorType = data.optInt("creatorType"),
        itemsName = data.string("itemsName"),
        thumbnailUrl = data.string("thumbnailUrl"),
        number = data.optInt("number"),
        remainingQuantity = data.optInt("remainingQuantity"),
        amount = data.decimal("amount"),
        transactionStatus = data.optInt("transactionStatus"),
        deliveryStatus = data.optInt("deliveryStatus"),
        locationName = data.string("locationName"),
        transactionLocationName = data.string("transactionLocationName"),
        deliveryMethod = data.optInt("deliveryMethod"),
        shippingFee = data.decimal("shippingFee"),
        createTime = data.string("createTime"),
        completionTime = data.string("completionTime"),
        cancellationTime = data.string("cancellationTime"),
        receiptDeadline = data.string("receiptDeadline"),
    )

    private fun requireSuccess(json: JSONObject) {
        if (json.optInt("code", -1) != 0) {
            throw IllegalStateException(json.optString("msg").ifBlank { "SCM 请求失败" })
        }
    }

    private fun JSONObject.string(key: String): String =
        if (isNull(key)) "" else optString(key)

    private fun JSONObject.decimal(key: String): BigDecimal =
        opt(key)?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

class TransactionClient {
    private fun api() = ScmAuthStore.api()

    fun create(draft: TransactionDraft): TransactionCreateResult {
        val body = JSONObject()
            .put("orderNumber", draft.orderNumber)
            .put("number", draft.number)
            .put("locationId", draft.locationId)
            .put("deliveryMethod", draft.deliveryMethod)
            .put("shippingFee", draft.shippingFee)
            .toString()
        return TransactionParser.parseCreate(request("POST", "/sc/order-transactions/create", body))
    }

    fun checkOngoing(orderNumber: String): Boolean {
        val encoded = URLEncoder.encode(orderNumber, "UTF-8")
        return TransactionParser.parseBoolean(
            request("GET", "/sc/order-transactions/check-ongoing?orderNumber=$encoded")
        )
    }

    fun page(
        pageNo: Int,
        pageSize: Int = 20,
        transactionStatus: Int? = null,
        orderNumber: String? = null,
    ): TransactionPage {
        val path = buildString {
            append("/sc/order-transactions/page?pageNo=$pageNo&pageSize=$pageSize&language=CN")
            transactionStatus?.let { append("&transactionStatus=$it") }
            orderNumber?.takeIf(String::isNotBlank)?.let {
                append("&orderNumber=").append(URLEncoder.encode(it, "UTF-8"))
            }
        }
        return TransactionParser.parsePage(request("GET", path))
    }

    fun get(transactionNumber: String): TransactionRecord {
        val encoded = URLEncoder.encode(transactionNumber, "UTF-8")
        return TransactionParser.parseDetail(
            request("GET", "/sc/order-transactions/get?transactionNumber=$encoded&language=CN")
        )
    }

    fun updateStatus(transactionNumber: String, deliveryStatus: Int) {
        val body = JSONObject()
            .put("transactionNumber", transactionNumber)
            .put("deliveryStatus", deliveryStatus)
            .toString()
        TransactionParser.parseVoid(request("PUT", "/sc/order-transactions/update-status", body))
    }

    fun approve(transactionNumber: String) {
        val encoded = URLEncoder.encode(transactionNumber, "UTF-8")
        TransactionParser.parseVoid(request("PUT", "/sc/order-transactions/approve?transactionNumber=$encoded"))
    }

    fun addressList(): List<AddressNode> =
        TransactionParser.parseAddresses(request("GET", "/sc/address/simple-list?language=CN"))

    private fun request(method: String, path: String, body: String? = null): JSONObject {
        val response = api().request(method, path, body)
        if (response.code !in 200..299) {
            throw IllegalStateException("SCM API 返回 ${response.code}")
        }
        return JSONObject(response.body)
    }
}
