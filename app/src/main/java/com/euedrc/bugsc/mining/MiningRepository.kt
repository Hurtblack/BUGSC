package com.euedrc.bugsc.mining

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 采矿数据仓库 —— 一次性从 assets / filesDir 缓存加载 5 个 JSON, 内建多套索引,
 * 提供面向 UI 的查询 API。
 *
 * 数据来源: sm.scmdb.net (英文主体) + flowcld SCM (中文翻译) + 手工地名表,
 * 由 tools/export_mining 系列脚本生成。
 *
 * 读取优先级 (仿 BlueprintDataRepository):
 *     filesDir/mining/<name>.json  (热更新缓存, 若 _version 更高)
 *     assets/mining/<name>.json    (内置快照, 兜底)
 *
 * 热更新:
 *   1. 维护者重跑 tools/export_mining_*.py → 5 个 JSON 都带新 _version
 *   2. 上传到 [RemoteConfig.baseUrl] 指向的目录
 *   3. App 调 [refreshFromRemote] 时下载 _version 更高的文件到缓存
 *   4. 下次 [get] 创建实例时自动读取新缓存 (重启或 [invalidate] 后生效)
 *
 * 单例: [Companion.get] —— 首次加载会扫一遍 ~400KB JSON (主线程 < 100ms, 建议放 IO)。
 *
 * 主要查询:
 *   - [getElementByName] / [searchElements] —— 找矿
 *   - [findElementOccurrences] —— "这个矿能在哪挖到, 概率如何"
 *   - [getLocationYields] —— "这个地点能挖到啥"
 *   - [elementsByRarity] —— 按稀有度过滤
 */
class MiningRepository private constructor(private val context: Context) {

    /** 一份可热更新的数据集。 */
    enum class Dataset(val fileName: String) {
        MINING_DATA("mining_data.json"),
        MINING_EQUIPMENT("mining_equipment.json"),
        ELEMENT_TRANSLATIONS("element_translations.json"),
        LOCATION_TRANSLATIONS("location_translations.json"),
        MANIFEST("manifest.json"),
    }

    /** 全部矿物, 按 GUID 索引。 */
    val elements: Map<String, MiningElement>
    /** 全部组合, 按 GUID 索引。 */
    val compositions: Map<String, MiningComposition>
    val locations: List<MiningLocation>

    val lasers: List<MiningLaser>
    val modules: List<MiningModule>
    val gadgets: List<MiningGadget>

    /** 矿物 GUID -> 包含它的组合列表。 */
    private val elementToComps: Map<String, List<MiningComposition>>
    /** 组合 GUID -> 引用它的 (location, group, deposit) 列表。 */
    private val compToLocs: Map<String, List<Triple<MiningLocation, MiningGroup, MiningDeposit>>>
    /** 矿物 GUID -> 全部出现行 (扁平), 提前算好。 */
    private val elementOccurrences: Map<String, List<ElementOccurrence>>

    val gameVersion: String?
    val build: String?

    init {
        val translations = bestJson(Dataset.ELEMENT_TRANSLATIONS)
            .optJSONObject("translations") ?: JSONObject()
        val locTr = bestJson(Dataset.LOCATION_TRANSLATIONS)
        val sysMap  = locTr.optJSONObject("systems")   ?: JSONObject()
        val locMap  = locTr.optJSONObject("locations") ?: JSONObject()
        val typeMap = locTr.optJSONObject("types")     ?: JSONObject()

        val manifest = bestJson(Dataset.MANIFEST)
        gameVersion = manifest.optString("gameVersion").takeIf { it.isNotBlank() }
        build = manifest.optString("build").takeIf { it.isNotBlank() }

        val data = bestJson(Dataset.MINING_DATA)
        elements = parseElements(data.getJSONObject("mineableElements"), translations)
        compositions = parseCompositions(data.getJSONObject("compositions"))
        locations = parseLocations(data.getJSONArray("locations"), sysMap, locMap, typeMap)

        val eq = bestJson(Dataset.MINING_EQUIPMENT)
        lasers = parseLasers(eq.optJSONArray("lasers") ?: JSONArray())
        modules = parseModules(eq.optJSONArray("modules") ?: JSONArray())
        gadgets = parseGadgets(eq.optJSONArray("gadgets") ?: JSONArray())

        // 索引
        val e2c = HashMap<String, MutableList<MiningComposition>>()
        for (c in compositions.values) {
            for (p in c.parts) e2c.getOrPut(p.elementGuid) { mutableListOf() }.add(c)
        }
        elementToComps = e2c

        val c2l = HashMap<String, MutableList<Triple<MiningLocation, MiningGroup, MiningDeposit>>>()
        for (loc in locations) {
            for (g in loc.groups) {
                for (dep in g.deposits) {
                    val cg = dep.compositionGuid ?: continue
                    c2l.getOrPut(cg) { mutableListOf() }.add(Triple(loc, g, dep))
                }
            }
        }
        compToLocs = c2l

        // 预算 occurrences
        val occ = HashMap<String, MutableList<ElementOccurrence>>()
        for ((eid, elem) in elements) {
            val list = occ.getOrPut(eid) { mutableListOf() }
            for (c in elementToComps[eid].orEmpty()) {
                val part = c.parts.first { it.elementGuid == eid }
                for ((loc, grp, dep) in compToLocs[c.guid].orEmpty()) {
                    list += ElementOccurrence(
                        element = elem, composition = c, part = part,
                        location = loc, groupName = grp.groupName,
                        probabilityInGroup = dep.probabilityInGroup,
                    )
                }
            }
        }
        elementOccurrences = occ

        Log.i(TAG, "loaded: ${elements.size} elements, ${compositions.size} compositions, " +
                "${locations.size} locations, ${lasers.size} lasers (SC $gameVersion-$build)")
    }

    // ──────────── 查询 API ────────────

    fun getElement(guid: String): MiningElement? = elements[guid]

    /** 精确按英文名取 (大小写敏感)。 */
    fun getElementByName(name: String): MiningElement? =
        elements.values.firstOrNull { it.nameEn == name }

    /** 按英中文模糊搜索 (大小写无关, 子串)。 */
    fun searchElements(keyword: String): List<MiningElement> {
        if (keyword.isBlank()) return elements.values.sortedBy { it.displayName }
        val q = keyword.lowercase()
        return elements.values.filter {
            it.nameEn.lowercase().contains(q) || (it.nameCn?.contains(keyword) == true)
        }.sortedBy { it.displayName }
    }

    fun elementsByRarity(rarity: String?): List<MiningElement> =
        elements.values.filter { it.rarity == rarity }.sortedBy { it.displayName }

    /** "这种矿物会在哪些地点出现, 概率与含量如何"。结果按综合概率降序。 */
    fun findElementOccurrences(elementGuid: String): List<ElementOccurrence> =
        elementOccurrences[elementGuid].orEmpty().sortedByDescending { it.overallProbability }

    /** 同上, 按英文名。 */
    fun findElementOccurrencesByName(name: String): List<ElementOccurrence> =
        getElementByName(name)?.let { findElementOccurrences(it.guid) } ?: emptyList()

    /** "这个地点能挖到哪些矿石组合, 各 composition 的占比+包含 element 列表"。 */
    fun getLocationYields(locationName: String): List<LocationYield> {
        val loc = locations.firstOrNull { it.locationName == locationName } ?: return emptyList()
        val out = mutableListOf<LocationYield>()
        for (g in loc.groups) {
            for (dep in g.deposits) {
                val cg = dep.compositionGuid ?: continue
                val comp = compositions[cg] ?: continue
                val elemPairs = comp.parts.mapNotNull { p ->
                    elements[p.elementGuid]?.let { it to p }
                }
                out += LocationYield(
                    location = loc, groupName = g.groupName,
                    composition = comp, probabilityInGroup = dep.probabilityInGroup,
                    elements = elemPairs,
                )
            }
        }
        return out.sortedByDescending { it.probabilityInGroup }
    }

    fun locationsInSystem(system: String): List<MiningLocation> =
        locations.filter { it.system.equals(system, ignoreCase = true) }
            .sortedBy { it.locationName }

    fun allSystems(): List<String> = locations.map { it.system }.toSortedSet().toList()

    // ──────────── 解析私有 ────────────

    private fun parseElements(obj: JSONObject, tr: JSONObject): Map<String, MiningElement> {
        val out = LinkedHashMap<String, MiningElement>(obj.length())
        for (guid in obj.keys()) {
            val e = obj.getJSONObject(guid)
            val nameEn = e.getString("name")
            out[guid] = MiningElement(
                guid = guid,
                nameEn = nameEn,
                nameCn = tr.optString(nameEn).takeIf { it.isNotBlank() },
                rarity = e.optString("rarity").takeIf { it.isNotBlank() },
                density = e.optDouble("density", 0.0),
                instability = e.optDouble("instability", 0.0),
                resistance = e.optDouble("resistance", 0.0),
                optimalWindowMidpoint = e.optDouble("optimalWindowMidpoint", 0.0),
                optimalWindowRandomness = e.optDouble("optimalWindowRandomness", 0.0),
                optimalWindowThinness = e.optDouble("optimalWindowThinness", 0.0),
                explosionMultiplier = e.optDouble("explosionMultiplier", 0.0),
                clusterFactor = e.optDouble("clusterFactor", 0.0),
                scanSignature = e.optIntOrNull("scanSignature"),
                fpsScanSignature = e.optIntOrNull("fpsScanSignature"),
                groundScanSignature = e.optIntOrNull("groundScanSignature"),
            )
        }
        return out
    }

    private fun parseCompositions(obj: JSONObject): Map<String, MiningComposition> {
        val out = LinkedHashMap<String, MiningComposition>(obj.length())
        for (guid in obj.keys()) {
            val c = obj.getJSONObject(guid)
            val partsArr = c.getJSONArray("parts")
            val parts = ArrayList<CompositionPart>(partsArr.length())
            for (i in 0 until partsArr.length()) {
                val p = partsArr.getJSONObject(i)
                parts += CompositionPart(
                    elementGuid = p.getString("elementGuid"),
                    elementName = p.optString("elementName"),
                    probability = p.optDouble("probability", 1.0),
                    minPercent = p.optDouble("minPercent", 0.0),
                    maxPercent = p.optDouble("maxPercent", 0.0),
                )
            }
            out[guid] = MiningComposition(
                guid = guid,
                name = c.optString("name"),
                minimumDistinctElements = c.optInt("minimumDistinctElements", 1),
                parts = parts,
            )
        }
        return out
    }

    private fun parseLocations(
        arr: JSONArray, sysMap: JSONObject, locMap: JSONObject, typeMap: JSONObject,
    ): List<MiningLocation> {
        val out = ArrayList<MiningLocation>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val groupsArr = l.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<MiningGroup>(groupsArr.length())
            for (gi in 0 until groupsArr.length()) {
                val g = groupsArr.getJSONObject(gi)
                val deps = g.optJSONArray("deposits") ?: JSONArray()
                // 先把 deposit 解出, 再算组内归一化概率
                val tmp = ArrayList<Triple<String?, String?, Double>>(deps.length())
                for (di in 0 until deps.length()) {
                    val d = deps.getJSONObject(di)
                    tmp += Triple(
                        d.optStringOrNull("compositionGuid"),
                        d.optStringOrNull("presetName"),
                        d.optDouble("relativeProbability", 0.0),
                    )
                }
                val total = tmp.sumOf { it.third }.takeIf { it > 0.0 } ?: 1.0
                val deposits = tmp.map { (cg, pn, rp) ->
                    MiningDeposit(
                        compositionGuid = cg, presetName = pn,
                        relativeProbability = rp,
                        probabilityInGroup = rp / total,
                    )
                }
                groups += MiningGroup(
                    groupName = g.optString("groupName"),
                    groupProbability = g.optDouble("groupProbability", 1.0),
                    deposits = deposits,
                )
            }
            val nameEn = l.optString("locationName")
            val sysEn  = l.optString("system")
            val typeEn = l.optString("locationType")
            out += MiningLocation(
                locationName = nameEn,
                locationNameCn = locMap.optString(nameEn).takeIf { it.isNotBlank() },
                system = sysEn,
                systemCn = sysMap.optString(sysEn).takeIf { it.isNotBlank() },
                locationType = typeEn,
                locationTypeCn = typeMap.optString(typeEn).takeIf { it.isNotBlank() },
                groups = groups,
            )
        }
        return out
    }

    private fun parseLasers(arr: JSONArray): List<MiningLaser> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        MiningLaser(
            name = o.optString("name"),
            size = o.optIntOrNull("size"),
            grade = o.optIntOrNull("grade"),
            manufacturer = o.optString("manufacturer").takeIf { it.isNotBlank() },
            raw = o,
        )
    }

    private fun parseModules(arr: JSONArray): List<MiningModule> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i); MiningModule(o.optString("name"), o)
    }

    private fun parseGadgets(arr: JSONArray): List<MiningGadget> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i); MiningGadget(o.optString("name"), o)
    }

    // ──────────── 文件读取: filesDir 缓存 vs assets 内置, 取 _version 较高者 ────────────

    private fun bestJson(ds: Dataset): JSONObject {
        val cached = readCache(ds)
        val asset = readAsset(ds)
        return when {
            cached == null -> asset ?: JSONObject()
            asset == null -> cached
            versionOf(cached) >= versionOf(asset) -> cached
            else -> asset
        }
    }

    private fun versionOf(json: JSONObject): Long = json.optLong("_version", 0L)

    private fun cacheFile(ds: Dataset): File =
        File(context.filesDir, "mining/${ds.fileName}").apply { parentFile?.mkdirs() }

    private fun readCache(ds: Dataset): JSONObject? = runCatching {
        val f = cacheFile(ds)
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    private fun readAsset(ds: Dataset): JSONObject? = runCatching {
        context.assets.open("mining/${ds.fileName}")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
    }.getOrNull()

    /** 当前生效数据集的版本号 (用于 UI 角标 / 调试)。 */
    fun currentVersion(ds: Dataset): Long = versionOf(bestJson(ds))

    // ──────────── 远程热更新 ────────────

    /**
     * 阻塞式从远程逐个数据集检查更新: 远程 _version 更高才下载并原子写入缓存。
     * 必须在 [Dispatchers.IO] 中调用。
     *
     * @return 实际更新的数据集列表; 未配置远程 / 全部已是最新返回空, 不抛异常。
     *         调用方拿到非空结果后, 建议提示用户重启 App 或调 [invalidate] 后重建仓库。
     */
    fun refreshFromRemote(): List<Dataset> {
        if (RemoteConfig.baseUrl.isBlank()) {
            Log.d(TAG, "远程地址未配置, 跳过热更新")
            return emptyList()
        }
        val updated = mutableListOf<Dataset>()
        for (ds in Dataset.entries) {
            runCatching { refreshOne(ds) }
                .onSuccess { if (it) updated += ds }
                .onFailure { Log.d(TAG, "更新 ${ds.fileName} 失败: ${it.message}") }
        }
        return updated
    }

    private fun refreshOne(ds: Dataset): Boolean {
        val localVersion = versionOf(bestJson(ds))
        val url = RemoteConfig.baseUrl.trimEnd('/') + "/" + ds.fileName
        val text = httpGet(url)
        val remote = JSONObject(text)
        val remoteVersion = versionOf(remote)
        if (remoteVersion <= localVersion) {
            Log.d(TAG, "${ds.fileName} 远程 $remoteVersion <= 本地 $localVersion, 跳过")
            return false
        }
        // 原子写: 临时文件 + rename
        val target = cacheFile(ds)
        val tmp = File(target.parentFile, ds.fileName + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text); tmp.delete()
        }
        Log.i(TAG, "${ds.fileName} 热更新 -> _version=$remoteVersion")
        return true
    }

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

    /** 远程数据托管配置 —— TODO: 部署后填入你自己的 CDN / GitHub raw 地址。 */
    object RemoteConfig {
        /**
         * 数据文件所在目录的基址, 文件名与 assets 一致。例如:
         *   "https://raw.githubusercontent.com/<you>/<repo>/main/mining"
         * 留空则完全使用本地数据, 不发起网络请求。
         */
        var baseUrl: String = ""
    }

    companion object {
        private const val TAG = "MiningRepository"

        @Volatile private var instance: MiningRepository? = null

        /** 线程安全单例。首次调用执行解析 (~50-100ms, 建议放 IO scope)。 */
        fun get(context: Context): MiningRepository =
            instance ?: synchronized(this) {
                instance ?: MiningRepository(context.applicationContext).also { instance = it }
            }

        /** 让下次 [get] 重新构建实例 (热更新后调用)。 */
        fun invalidate() { synchronized(this) { instance = null } }
    }
}

// ──────────── JSONObject 小辅助 ────────────

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
