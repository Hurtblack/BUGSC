package com.euedrc.bugsc.wikelo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 维克洛兑换数据仓库 —— 单例, 首次构造时加载并索引两份 JSON。
 *
 * 数据来源: 巴努交易清单 4.8.0 LIVE V1 (Bilibili @Kitsune 泛星际报)。
 *
 * 加载量级: 两个文件合计 ~55KB, 主线程解析 < 50ms; 仍建议放 IO scope。
 *
 * 主要查询:
 *   - [allTrades] / [tradesByBranch] / [tradesByCategory] —— 浏览
 *   - [search] —— 模糊匹配 (任务名/获得物/材料名)
 *   - [acquisitionFor] —— 材料反查: "这个材料从哪获取"
 *   - [categoriesIn] —— 给定 branch 下的所有 category, 顺序保留原 JSON 出现顺序
 */
class WikeloRepository private constructor(context: Context) {

    val trades: List<WikeloTrade>
    val materials: List<WikeloMaterialInfo>
    val repTiers: List<WikeloRepTier>
    val version: String?
    val needsVerification: List<String>
    val systemNotes: List<String>

    /** 材料中文名 -> 获取途径并集 (合并 vehicle/other 两表的重名条目)。 */
    private val acquisitionIndex: Map<String, List<String>>

    init {
        val trJson = readJson(context, "wikelo/banu_trades.json")
        val maJson = readJson(context, "wikelo/banu_materials.json")

        val meta = trJson.optJSONObject("meta")
        version = meta?.optString("version")?.takeIf { it.isNotBlank() }

        needsVerification = parseStringArr(trJson.optJSONArray("needs_verification"))
        systemNotes = parseStringArr(trJson.optJSONArray("system_notes"))
        repTiers = parseRepTiers(trJson.optJSONArray("reputation_tiers") ?: JSONArray())

        val tradesOut = ArrayList<WikeloTrade>()
        trJson.optJSONArray("prerequisites")?.let { parseTrades(it, tradesOut) }
        trJson.optJSONArray("trades")?.let { parseTrades(it, tradesOut) }
        trades = tradesOut

        materials = parseMaterials(maJson.optJSONArray("materials") ?: JSONArray())

        // 构建获取途径索引: 同名材料 (vehicle + other 同时收录的) 合并去重, 保序
        val acc = LinkedHashMap<String, LinkedHashSet<String>>()
        for (m in materials) {
            val bag = acc.getOrPut(m.nameCn) { LinkedHashSet() }
            bag.addAll(m.acquisition)
        }
        acquisitionIndex = acc.mapValues { (_, v) -> v.toList() }

        Log.i(
            TAG,
            "loaded: ${trades.size} trades, ${materials.size} materials, " +
                    "${acquisitionIndex.size} unique material names (Wikelo $version)"
        )
    }

    // ──────────── 查询 API ────────────

    fun allTrades(): List<WikeloTrade> = trades

    fun tradesByBranch(branch: WikeloBranch?): List<WikeloTrade> =
        if (branch == null) trades else trades.filter { it.branch == branch }

    /** 给定 branch 下, 所有出现过的 category, 保持首次出现顺序。 */
    fun categoriesIn(branch: WikeloBranch?): List<String> {
        val seen = LinkedHashSet<String>()
        for (t in trades) {
            if (branch == null || t.branch == branch) seen.add(t.category)
        }
        return seen.toList()
    }

    fun tradesByCategory(branch: WikeloBranch?, category: String?): List<WikeloTrade> {
        return trades.filter {
            (branch == null || it.branch == branch) &&
                    (category == null || it.category == category)
        }
    }

    /** 模糊搜索: 匹配任务中英文名 / 获得物 / 材料名 (子串, 大小写无关)。 */
    fun search(keyword: String): List<WikeloTrade> {
        if (keyword.isBlank()) return trades
        val q = keyword.trim().lowercase()
        return trades.filter { t ->
            val hay = buildString {
                append(t.nameCn).append('|').append(t.nameEn).append('|')
                t.rewardItem?.let { append(it).append('|') }
                for (m in t.materials) append(m.nameCn).append('|')
            }.lowercase()
            hay.contains(q)
        }
    }

    /** 材料反查: 该材料的获取途径 (合并各分类来源)。 */
    fun acquisitionFor(materialNameCn: String): List<String>? {
        acquisitionIndex[materialNameCn]?.let { return it }
        // 模糊回退: 去掉 (纯)/(完好)/全套 等后缀再找
        val base = materialNameCn
            .replace(Regex("[（(].*?[)）]"), "")
            .replace(Regex("全套|套装"), "")
            .trim()
        if (base.isEmpty()) return null
        for ((k, v) in acquisitionIndex) {
            if (k.contains(base) || base.contains(k)) return v
        }
        return null
    }

    /** 当前版本不可用任务计数 (供 UI 提示用)。 */
    fun unavailableCount(): Int = trades.count { !it.available }

    // ──────────── 解析私有 ────────────

    private fun parseTrades(arr: JSONArray, out: MutableList<WikeloTrade>) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val branchKey = o.optString("branch")
            val branch = WikeloBranch.fromKey(branchKey)
            if (branch == null) {
                Log.w(TAG, "unknown branch: $branchKey @ ${o.optString("name_cn")}")
                continue
            }

            val matsArr = o.optJSONArray("materials") ?: JSONArray()
            val mats = ArrayList<WikeloMaterialReq>(matsArr.length())
            for (mi in 0 until matsArr.length()) {
                val mo = matsArr.getJSONObject(mi)
                mats += WikeloMaterialReq(
                    nameCn = mo.optString("name_cn"),
                    qty = mo.optIntOrNull("qty"),
                    unit = mo.optStringOrNull("unit"),
                )
            }
            val rep = o.optJSONObject("reputation")
            out += WikeloTrade(
                nameEn = o.optString("name_en"),
                nameCn = o.optString("name_cn"),
                branch = branch,
                category = o.optString("category"),
                available = o.optBoolean("available", true),
                rewardItem = o.optStringOrNull("reward_item"),
                favorCost = o.optIntOrNull("favor_cost"),
                materials = mats,
                reputation = WikeloReputation(
                    reward = rep?.optIntOrNull("reward"),
                    requiredTier = rep?.optIntOrNull("required_tier"),
                ),
                newThisVersion = o.optBoolean("new_this_version", false),
                notes = o.optStringOrNull("notes"),
                imageAsset = o.optStringOrNull("image_asset"), // 数据里暂无, 为图片功能预留
            )
        }
    }

    private fun parseMaterials(arr: JSONArray): List<WikeloMaterialInfo> {
        val out = ArrayList<WikeloMaterialInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val acqArr = o.optJSONArray("acquisition") ?: JSONArray()
            val acq = ArrayList<String>(acqArr.length())
            for (ai in 0 until acqArr.length()) acq += acqArr.getString(ai)
            out += WikeloMaterialInfo(
                nameEn = o.optString("name_en"),
                nameCn = o.optString("name_cn"),
                category = o.optString("category"),
                acquisition = acq,
                note = o.optStringOrNull("note"),
                synthesis = o.optStringOrNull("synthesis"),
            )
        }
        return out
    }

    private fun parseRepTiers(arr: JSONArray): List<WikeloRepTier> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        WikeloRepTier(
            level = o.optInt("level"),
            nameCn = o.optString("name_cn"),
            nameEn = o.optString("name_en"),
            threshold = o.optInt("threshold"),
        )
    }

    private fun parseStringArr(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { i -> arr.optString(i) }
    }

    private fun readJson(context: Context, assetPath: String): JSONObject {
        context.assets.open(assetPath).use { ins ->
            val text = BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readText()
            return JSONObject(text)
        }
    }

    companion object {
        private const val TAG = "WikeloRepository"

        @Volatile private var instance: WikeloRepository? = null

        /** 线程安全单例。首次调用执行解析 (~30-50ms, 建议放 IO scope)。 */
        fun get(context: Context): WikeloRepository =
            instance ?: synchronized(this) {
                instance ?: WikeloRepository(context.applicationContext).also { instance = it }
            }
    }
}

// ──────────── JSONObject 小辅助 (与 mining 模块同形式) ────────────

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
