package com.euedrc.bugsc.wb

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 每日 WB（RSI 官网 warbond 限时折扣船）数据仓库 —— 支持热更新，运行时不直连 RSI。
 *
 * 读取优先级：**filesDir 缓存（最新下载） > assets 内置快照**，按数据里的 `version` 取较高者
 * （version 为生成时的 epoch 秒，单调递增）。远程拉取失败时自动回退本地，功能不受影响。
 *
 * 数据由 CI 脚本 `tools/export_daily_wb.py` 定时抓 RSI 产出，托管在公开仓库的 raw 地址
 * （见 [RemoteConfig.url]）。脚本只吐英文船名，中文名由本类用 app 已有翻译表对齐。
 *
 * 注意：[refreshFromRemote] 为阻塞式，请在协程的 Dispatchers.IO 中调用。
 */
class WbRepository(private val context: Context) {

    /** 一条 warbond 折扣船。价格单位为美元主价；缺失字段为 null。 */
    data class WbItem(
        val nameEn: String,
        val nameZh: String?,
        val warbondPrice: Double?,
        val standardPrice: Double?,
        val currency: String,
        val url: String?,
        val thumbnail: String?,
    ) {
        /** 列表展示用：有中文名则「中文 (English)」，否则只英文。 */
        val displayName: String
            get() = if (!nameZh.isNullOrBlank()) "$nameZh ($nameEn)" else nameEn
    }

    // ---- 对外读取 API（同步、纯本地，建议放后台）----

    /** 加载 warbond 船列表（缓存优先，assets 兜底），中文名已对齐。无数据返回空表。 */
    fun loadWbItems(): List<WbItem> {
        val root = bestJson()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val aliases = readShipFitAliases()
        val out = ArrayList<WbItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nameEn = o.optString("nameEn").takeIf { it.isNotBlank() } ?: continue
            out += WbItem(
                nameEn = nameEn,
                nameZh = aliases[nameEn]?.takeIf { it.isNotBlank() },
                warbondPrice = o.optDoubleOrNull("warbondPrice"),
                standardPrice = o.optDoubleOrNull("standardPrice"),
                currency = o.optString("currency").ifBlank { "USD" },
                url = o.optString("url").takeIf { it.isNotBlank() },
                thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    /** 当前生效数据的生成时间（UTC ISO 字符串）；无则 null。用于 UI 展示「更新于」。 */
    fun generatedAt(): String? = bestJson().optString("generatedAt").takeIf { it.isNotBlank() }

    // ---- 远程热更新 ----

    /**
     * 从远程拉最新 JSON，远程 version 更高才原子写入缓存。
     * @return true 表示有更新落盘；远程未配置 / 失败 / 版本不更高时返回 false。
     * @throws Exception 网络/解析失败时抛出（供 UI toast 提示），调用方需 catch。
     */
    fun refreshFromRemote(): Boolean {
        if (RemoteConfig.url.isBlank()) {
            debug("远程地址未配置，跳过热更新")
            return false
        }
        val text = httpGet(RemoteConfig.url)
        val remote = JSONObject(text)
        // 远程必须有 items（哪怕空也算合法），否则视为脏数据拒绝写入
        if (!remote.has("items")) throw IllegalStateException("远程数据缺少 items 字段")
        val remoteVersion = versionOf(remote)
        val localVersion = versionOf(bestJson())
        if (remoteVersion <= localVersion) {
            debug("远程版本 $remoteVersion <= 本地 $localVersion，无需更新")
            return false
        }
        val target = cacheFile()
        val tmp = File(target.parentFile, FILE_NAME + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text); tmp.delete()
        }
        cachedJson = null
        debug("已更新到版本 $remoteVersion")
        return true
    }

    // ---- 内部：取「缓存 vs assets」中版本较高者 ----

    private fun bestJson(): JSONObject {
        cachedJson?.let { return it }
        val cached = readCache()
        val asset = readAsset()
        val best = when {
            cached == null -> asset ?: JSONObject()
            asset == null -> cached
            versionOf(cached) >= versionOf(asset) -> cached
            else -> asset
        }
        cachedJson = best
        return best
    }

    private fun versionOf(json: JSONObject): Long = json.optLong("version", 0L)

    private fun cacheFile(): File =
        File(context.filesDir, "wb/$FILE_NAME").apply { parentFile?.mkdirs() }

    private fun readCache(): JSONObject? = runCatching {
        val f = cacheFile()
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    private fun readAsset(): JSONObject? = runCatching {
        context.assets.open("wb/$FILE_NAME")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
    }.getOrNull()

    /** 复用 ShipFit 的中文别名表（ships + components），en → zh。 */
    private fun readShipFitAliases(): Map<String, String> = runCatching {
        val root = context.assets.open("shipfit/zh_aliases.json")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
        val out = LinkedHashMap<String, String>()
        fun append(obj: JSONObject?) {
            if (obj == null) return
            for (key in obj.keys()) {
                val value = obj.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) out[key] = value
            }
        }
        append(root.optJSONObject("ships"))
        append(root.optJSONObject("components"))
        out
    }.getOrDefault(emptyMap())

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun debug(msg: String) = Log.d(TAG, msg)

    /** 远程数据托管配置。 */
    object RemoteConfig {
        /** `daily_wb.json` 的 raw 完整地址。留空则完全使用本地数据，不发起网络请求。 */
        var url: String = "https://raw.githubusercontent.com/Hurtblack/BUGSC/main/wb/daily_wb.json"
    }

    companion object {
        private const val TAG = "WbData"
        private const val FILE_NAME = "daily_wb.json"

        @Volatile
        private var cachedJson: JSONObject? = null
    }
}

/** org.json 没有可空 optDouble，缺失/NaN 时返回 null。 */
private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key, Double.NaN)
    return if (v.isNaN()) null else v
}
