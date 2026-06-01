package com.euedrc.bugsc.blueprint

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * UEX API 2.0 网络客户端。
 *
 * 风格对齐项目内 RsiInventoryClient:HttpURLConnection + org.json,不引入第三方网络库。
 *
 * - 只读 GET 接口;Bearer token 可选(目前读接口裸调可通,带上更稳、限流更宽)。
 * - 方法为阻塞式,请在协程的 Dispatchers.IO 中调用(见 [com.euedrc.bugsc.blueprint] 使用说明)。
 * - 解析交给 [UexDto];转领域模型交给 [UexMapper]。
 */
class UexClient(
    private val accessToken: String? = null,
) {
    private val debugLines = mutableListOf<String>()

    fun debugLog(): String = debugLines.takeLast(30).joinToString("\n")

    /** GET /categories —— 全部分类。 */
    fun getCategories(): List<UexCategory> =
        UexDto.parseCategories(getJson("categories"))

    /** GET /items?id_category= —— 某分类下全部物品。 */
    fun getItemsByCategory(idCategory: Int): List<UexItem> =
        UexDto.parseItems(getJson("items", mapOf("id_category" to idCategory.toString())))

    /** GET /items_attributes?id_category= —— 某分类下全部物品属性行。 */
    fun getItemAttributesByCategory(idCategory: Int): List<UexItemAttribute> =
        UexDto.parseItemAttributes(
            getJson("items_attributes", mapOf("id_category" to idCategory.toString()))
        )

    /** 按 uuid 取单个物品。 */
    fun getItemByUuid(uuid: String): UexItem? =
        UexDto.parseItems(getJson("items", mapOf("uuid" to uuid))).firstOrNull()

    /**
     * 便捷方法:一次拉取某分类的物品 + 属性,组装成带中文的领域模型。
     * 适合「点开某个分类 → 展示图鉴列表」的场景。
     */
    fun getCodexByCategory(
        idCategory: Int,
        translations: CodexTranslations = CodexTranslations.EMPTY,
    ): List<CodexItem> {
        val items = getItemsByCategory(idCategory)
        val attrs = getItemAttributesByCategory(idCategory)
        return UexMapper.toItems(items, attrs, translations)
    }

    private fun getJson(resource: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = openConnection("$resource/$query")
        return try {
            conn.requestMethod = "GET"
            val text = readResponse(conn)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (!accessToken.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
        debug("${conn.requestMethod} ${conn.url} -> $code, bytes=${body.length}")
        if (code !in 200..299) {
            val message = runCatching {
                val json = JSONObject(body)
                json.optString("message").ifEmpty { json.optString("status") }
            }.getOrNull().orEmpty()
            throw Exception(message.ifEmpty { "UEX 请求失败($code)" })
        }
        // UEX 业务错误:http 200 但 status != ok(如缺参数)
        val status = runCatching { JSONObject(body).optString("status") }.getOrNull()
        if (!status.isNullOrEmpty() && status != "ok") {
            throw Exception("UEX 返回异常:$status")
        }
        return body
    }

    private fun debug(message: String) {
        debugLines += message
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "UexClient"
        private const val BASE_URL = "https://api.uexcorp.uk/2.0/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
