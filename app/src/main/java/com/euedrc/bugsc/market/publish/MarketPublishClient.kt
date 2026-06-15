package com.euedrc.bugsc.market.publish

import com.euedrc.bugsc.scm.ScmAuthStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
data class UploadKey(val tempKey: String, val expiresIn: Long, val expiresAt: Long)
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

class MarketPublishClient(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun searchItems(keyword: String): List<ItemSearchResult> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val resp = api().request("GET", "/product/items/item-search?name=$encoded&language=CN")
        val arr = requireSuccess(JSONObject(resp.body)).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                ItemSearchResult(
                    id = it.optLong("id"),
                    itemName = it.optString("itemName"),
                    itemNameEn = it.optString("itemNameEn"),
                    thumbnailUrl = it.optString("thumbnailUrl"),
                    thumbnailUrlHd = it.optString("thumbnailUrlHd"),
                )
            }
        }.filter { it.id > 0 && it.itemName.isNotBlank() }
    }

    fun createItemList(title: String, items: List<PublishItemValue>): Long {
        val body = MarketPublishJson.itemListBody(title, items)
        val response = api().request("POST", "/sc/item-list/create", body)
        return requireSuccess(JSONObject(response.body)).optLong("data")
    }

    fun createOrder(
        creatorType: PublishCreatorType,
        locationId: Long,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        items: List<PublishItemValue>,
        manifestId: Long? = null,
    ): Long {
        val body = MarketPublishJson.orderBody(creatorType, locationId, status, expireTime, items, manifestId)
        val response = api().request("POST", "/sc/orders/create", body)
        return requireSuccess(JSONObject(response.body)).optLong("data")
    }

    fun ownOrders(pageNo: Int, pageSize: Int = 20, creatorType: PublishCreatorType? = null): PublishOrderPage {
        val path = buildString {
            append("/sc/orders/page-of-own?pageNo=$pageNo&pageSize=$pageSize")
            append("&language=CN&ignoreStatus=true&includeDeleted=false")
            creatorType?.let { append("&creatorType=").append(it.apiValue) }
        }
        val root = requireSuccess(JSONObject(api().request("GET", path).body))
        val data = root.optJSONObject("data") ?: return PublishOrderPage(emptyList(), 0)
        val arr = data.optJSONArray("list") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseOwnOrder) }
        return PublishOrderPage(items, data.optLong("total"))
    }

    fun updateOrder(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    ) {
        requireSuccess(
            JSONObject(
                api().request(
                    "PUT",
                    "/sc/orders/update",
                    MarketPublishJson.updateBody(orderNumber, unitPrice, remainingQuantity, status, expireTime, quality),
                ).body
            )
        )
    }

    fun setOrderStatus(orderNumber: String, status: PublishOrderStatus) {
        val encoded = URLEncoder.encode(orderNumber, "UTF-8")
        requireSuccess(JSONObject(api().request("PUT", "/sc/orders/orderStatus?orderNumber=$encoded&status=${status.apiValue}").body))
    }

    fun deleteOrder(orderNumber: String) {
        val encoded = URLEncoder.encode(orderNumber, "UTF-8")
        requireSuccess(JSONObject(api().request("DELETE", "/sc/orders/delete?orderNumber=$encoded").body))
    }

    fun quantityAdd(orderNumber: String) {
        val encoded = URLEncoder.encode(orderNumber, "UTF-8")
        requireSuccess(JSONObject(api().request("PUT", "/sc/orders/quantity-add?orderNumber=$encoded").body))
    }

    fun imageStatus(itemId: Long): ItemImageStatus? {
        val root = requireSuccess(JSONObject(api().request("GET", "/member/item/image/status?itemId=$itemId").body))
        val data = root.optJSONObject("data") ?: return null
        return ItemImageStatus(
            itemId = data.optLong("itemId"),
            itemName = data.optString("itemName"),
            thumbnailUrl = data.optString("thumbnailUrl"),
            thumbnailUrlHd = data.optString("thumbnailUrlHd"),
            status = if (data.isNull("status")) null else data.optInt("status"),
            rejectReason = data.optString("rejectReason"),
        )
    }

    fun generateUploadKey(): UploadKey {
        val request = Request.Builder()
            .url("$WORKER_BASE_URL/generate-key")
            .get()
            .header("Authorization", bearer())
            .build()
        okHttp.newCall(request).execute().use { response ->
            val root = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) throw IllegalStateException(root.optString("msg").ifBlank { "获取上传密钥失败" })
            val data = requireSuccess(root).optJSONObject("data") ?: throw IllegalStateException("获取上传密钥失败")
            return UploadKey(data.optString("tempKey"), data.optLong("expiresIn"), data.optLong("expiresAt"))
        }
    }

    fun uploadImage(itemId: Long, imageFile: File, mimeType: String): UploadResult {
        require(imageFile.exists() && imageFile.length() > 0) { "图片文件不存在" }
        require(imageFile.length() <= MAX_IMAGE_BYTES) { "文件大小不能超过 1MB" }
        require(mimeType in ALLOWED_MIME_TYPES) { "只支持 JPG、PNG、WebP 格式" }
        val key = generateUploadKey()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", imageFile.name, imageFile.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("itemId", itemId.toString())
            .addFormDataPart("tempKey", key.tempKey)
            .build()
        val request = Request.Builder()
            .url(WORKER_BASE_URL)
            .post(body)
            .header("Authorization", bearer())
            .build()
        okHttp.newCall(request).execute().use { response ->
            val root = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) throw IllegalStateException(root.optString("msg").ifBlank { "上传失败" })
            val data = requireSuccess(root).optJSONObject("data")
            return UploadResult(data?.optString("message").orEmpty().ifBlank { "提交成功，等待审核" })
        }
    }

    private fun parseOwnOrder(data: JSONObject): OwnMarketOrder {
        val type = if (data.optInt("creatorType") == 0) PublishCreatorType.BUY else PublishCreatorType.SELL
        val status = if (data.optInt("status") == 0) PublishOrderStatus.HIDDEN else PublishOrderStatus.VISIBLE
        return OwnMarketOrder(
            orderNumber = data.optString("orderNumber"),
            creatorType = type,
            itemName = data.optString("itemName"),
            remainingQuantity = data.optInt("remainingQuantity"),
            unitPrice = data.opt("unitPrice")?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            status = status,
            createTime = data.optString("createTime"),
        )
    }

    private fun requireSuccess(root: JSONObject): JSONObject {
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("msg").ifBlank { "SCM 请求失败" })
        }
        return root
    }

    private fun api() = ScmAuthStore.api()

    private fun bearer(): String = "Bearer ${ScmAuthStore.session().accessToken}"

    companion object {
        const val WORKER_BASE_URL = "https://scmitem.flowcld.com"
        const val MAX_IMAGE_BYTES = 1 * 1024 * 1024L
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
