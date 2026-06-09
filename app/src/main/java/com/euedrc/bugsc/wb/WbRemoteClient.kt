package com.euedrc.bugsc.wb

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 每日 WB 的**手机端直连抓取**客户端（匿名，无需登录）。
 *
 * 背景：把抓取放 GitHub Actions（数据中心 IP）会被 RSI 按 IP 信誉 403 拦截；而用户手机是住宅/移动
 * IP，不在黑名单。故由 app「同步」时在本机走完整链路，仓库内 assets 快照仅作首次/离线兜底。
 *
 * 链路（对应 tools/export_daily_wb.py，全自动匿名）：
 *   1. GET  /en/pledge                              → Rsi-Token cookie + <meta csrf-token>
 *   2. POST /api/account/v2/setAuthToken      {}    → Rsi-Account-Auth cookie（匿名 USD 定价）
 *   3. POST /api/ship-upgrades/setContextToken {}   → Rsi-ShipUpgrades-Context cookie（mode:browse）
 *   4. POST /pledge-store/api/upgrade/v2/graphql    → 全船 + SKU 价格（price 单位是分）
 *   5. POST /graphql store.search（browse）         → 按 ship id 补 购买链接 + 缩略图
 *
 * warbond 船 = upgrade v2 里某 ship 的 skus 中有 title 含 "Warbond" 的（Warbond=优惠价 / 其余取最高=原价）。
 *
 * 注意：阻塞式，请在 Dispatchers.IO 调用。失败抛 [WbFetchException]。
 */
class WbRemoteClient {

    class WbFetchException(message: String) : Exception(message)

    // 累积的匿名会话 cookie
    private var rsiToken = ""
    private var accountAuth = ""
    private var upgradeContext = ""
    private var csrf = ""

    /**
     * 走完整链路，产出与 daily_wb.json 同契约的 JSONObject（version/generatedAt/items）。
     * @throws WbFetchException 任一步失败或解析出 0 条 warbond 时。
     */
    fun fetch(): JSONObject {
        openSession()
        primeTokens()
        val warbond = fetchWarbondShips()
        if (warbond.isEmpty()) throw WbFetchException("解析出 0 条 warbond 船（疑似 RSI schema 变更）")
        val browse = fetchBrowseIndex(warbond.keys)

        val items = JSONArray()
        for ((id, w) in warbond) {
            val extra = browse[id]
            items.put(JSONObject().apply {
                put("nameEn", w.nameEn)
                put("warbondPrice", w.warbondCents?.let { it / 100.0 } ?: JSONObject.NULL)
                put("standardPrice", w.standardCents?.let { it / 100.0 } ?: JSONObject.NULL)
                put("currency", "USD")
                put("url", extra?.url ?: JSONObject.NULL)
                put("thumbnail", extra?.thumbnail ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("version", System.currentTimeMillis() / 1000)
            put("generatedAt", isoNow())
            put("items", items)
        }
    }

    // ---- 步骤 1：建立会话，取 Rsi-Token + csrf ----
    private fun openSession() {
        val html = httpGet(PLEDGE_URL)
        csrf = Regex("""csrf-token"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (csrf.isBlank()) throw WbFetchException("找不到 csrf-token（被拦截或页面结构变更）")
        if (rsiToken.isBlank()) throw WbFetchException("未拿到 Rsi-Token cookie")
    }

    // ---- 步骤 2、3：匿名账户 token + 浏览上下文 token ----
    private fun primeTokens() {
        httpPostJson(AUTH_TOKEN_URL, "{}")
        httpPostJson(CONTEXT_TOKEN_URL, "{}")
    }

    private data class WbShip(val nameEn: String, val warbondCents: Int?, val standardCents: Int?)

    // ---- 步骤 4：upgrade v2 取全船，筛 warbond ----
    private fun fetchWarbondShips(): LinkedHashMap<String, WbShip> {
        val data = gql(UPGRADE_GQL, UPGRADE_QUERY, null)
        val ships = data.optJSONObject("to")?.optJSONArray("ships") ?: JSONArray()
        val out = LinkedHashMap<String, WbShip>()
        for (i in 0 until ships.length()) {
            val s = ships.optJSONObject(i) ?: continue
            val skus = s.optJSONArray("skus") ?: continue
            var wbCents: Int? = null
            var maxOther: Int? = null
            for (j in 0 until skus.length()) {
                val k = skus.optJSONObject(j) ?: continue
                val title = k.optString("title")
                val price = if (k.has("price") && !k.isNull("price")) k.optInt("price") else null
                if (title.contains("warbond", ignoreCase = true)) {
                    if (price != null) wbCents = price
                } else if (price != null) {
                    if (maxOther == null || price > maxOther) maxOther = price
                }
            }
            if (wbCents == null) continue
            out[s.optString("id")] = WbShip(s.optString("name"), wbCents, maxOther)
        }
        return out
    }

    private data class BrowseEntry(val url: String?, val thumbnail: String?)

    // ---- 步骤 5：/graphql browse 取链接 + 缩略图，找齐目标 id 即提前停 ----
    private fun fetchBrowseIndex(wantIds: Set<String>): Map<String, BrowseEntry> {
        val index = HashMap<String, BrowseEntry>()
        var page = 1
        while (page <= MAX_BROWSE_PAGES) {
            val variables = JSONObject().apply {
                put("query", JSONObject().apply {
                    put("page", page)
                    put("limit", 30)
                    put("ships", JSONObject().apply { put("all", true) })
                })
            }
            val data = runCatching { gql(STORE_GQL, BROWSE_QUERY, variables) }.getOrNull() ?: break
            val resources = data.optJSONObject("store")?.optJSONObject("search")?.optJSONArray("resources")
            if (resources == null || resources.length() == 0) break
            for (i in 0 until resources.length()) {
                val r = resources.optJSONObject(i) ?: continue
                val sid = r.optString("id")
                var thumb: String? = null
                r.optJSONArray("imageComposer")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val ic = arr.optJSONObject(j) ?: continue
                        if (ic.optString("slot") == "thumbnail" && ic.optString("url").isNotBlank()) {
                            thumb = ic.optString("url"); break
                        }
                    }
                }
                index[sid] = BrowseEntry(abs(r.optString("url")), abs(thumb))
            }
            if (index.keys.containsAll(wantIds)) break
            page++
        }
        return index
    }

    // ---- GraphQL ----
    private fun gql(url: String, query: String, variables: JSONObject?): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            if (variables != null) put("variables", variables)
        }
        val text = httpPostJson(url, payload.toString())
        val doc = if (text.isBlank()) JSONObject() else JSONObject(text)
        doc.optJSONArray("errors")?.let { if (it.length() > 0) throw WbFetchException("GraphQL 报错 @ $url: ${it.toString().take(200)}") }
        return doc.optJSONObject("data") ?: throw WbFetchException("GraphQL 无 data @ $url")
    }

    // ---- HTTP ----
    private fun httpGet(url: String): String {
        val conn = open(url)
        return try {
            conn.requestMethod = "GET"
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(url: String, body: String): String {
        val conn = open(url)
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (csrf.isNotBlank()) conn.setRequestProperty("X-CSRF-TOKEN", csrf)
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", PLEDGE_URL)
        conn.setRequestProperty("Accept", "*/*")
        cookieHeader().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        captureCookies(conn)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        Log.d(TAG, "${conn.requestMethod} ${conn.url} -> $code (${body.length}B)")
        if (code !in 200..299) {
            throw WbFetchException("HTTP $code @ ${conn.url}：${body.take(120)}")
        }
        return body
    }

    private fun captureCookies(conn: HttpURLConnection) {
        conn.headerFields
            .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values.flatten()
            .forEach { c ->
                val pair = c.substringBefore(";")
                val name = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                when (name) {
                    "Rsi-Token" -> rsiToken = value
                    "Rsi-Account-Auth" -> accountAuth = value
                    "Rsi-ShipUpgrades-Context" -> upgradeContext = value
                }
            }
    }

    private fun cookieHeader(): String = buildList {
        if (rsiToken.isNotBlank()) add("Rsi-Token=$rsiToken")
        if (accountAuth.isNotBlank()) add("Rsi-Account-Auth=$accountAuth")
        if (upgradeContext.isNotBlank()) add("Rsi-ShipUpgrades-Context=$upgradeContext")
    }.joinToString(";")

    private fun abs(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> BASE + path
    }

    private fun isoNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    companion object {
        private const val TAG = "WbRemote"
        private const val BASE = "https://robertsspaceindustries.com"
        private const val PLEDGE_URL = "$BASE/en/pledge"
        private const val AUTH_TOKEN_URL = "$BASE/api/account/v2/setAuthToken"
        private const val CONTEXT_TOKEN_URL = "$BASE/api/ship-upgrades/setContextToken"
        private const val UPGRADE_GQL = "$BASE/pledge-store/api/upgrade/v2/graphql"
        private const val STORE_GQL = "$BASE/graphql"
        private const val MAX_BROWSE_PAGES = 12
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private const val UPGRADE_QUERY =
            "query{ to{ ships{ id name skus{ id title price upgradePrice } } } }"
        private const val BROWSE_QUERY =
            "query(\$query: SearchQuery!){ store(name:\"pledge\", browse:true){ " +
                "search(query:\$query){ resources{ ... on RSIShip{ " +
                "id url imageComposer{ slot url } } } } } }"
    }
}
