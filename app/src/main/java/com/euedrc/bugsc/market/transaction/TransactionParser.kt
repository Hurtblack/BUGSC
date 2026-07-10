package com.euedrc.bugsc.market.transaction

import org.json.JSONObject
import java.math.BigDecimal

object TransactionParser {
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
