package com.euedrc.bugsc.blueprint

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 蓝图 / 翻译数据仓库 —— 支持热更新,运行时不依赖第三方站点。
 *
 * 读取优先级:**filesDir 缓存(最新下载) > assets 内置快照**,按数据里的
 * `version` 取较高者。远程拉取失败时自动回退本地,功能不受影响。
 *
 * 维护流程(游戏更新时):重跑导出脚本生成新 JSON(version+1)→ 上传到你自己的
 * 托管地址([RemoteConfig.baseUrl])→ 用户 App 下次 [refreshFromRemote] 时自动更新。
 * 不需要发布新版 App。
 *
 * 注意:网络方法为阻塞式,请在协程的 Dispatchers.IO 中调用。
 */
class BlueprintDataRepository(private val context: Context) {

    /** 一个可热更新的数据集定义。 */
    enum class Dataset(val fileName: String, val payloadHint: String) {
        TRANSLATIONS("scm_translations.json", "items_by_en"),
        SLOT_TRANSLATIONS("slot_translations.json", "slots"),
        BLUEPRINTS("scm_blueprint_hints.json", "blueprints"),
        MISSIONS("scm_blueprint_missions.json", "missions"),
        SCCRAFT("sccraft_blueprints.json", "blueprints"),
        MISSION_TRANSLATIONS("mission_translations.json", "missions"),
        ITEM_STATS("item_base_stats.json", "stats");
    }

    // ---- 对外读取 API（同步、纯本地，可在主线程调用，但建议放后台）----

    /** 加载中文翻译表(缓存优先,assets 兜底)。 */
    fun loadTranslations(): CodexTranslations =
        CodexTranslations.fromJson(bestJson(Dataset.TRANSLATIONS))
            .withItemNames(readShipFitAliases(), overrideExisting = false)

    /**
     * 加载任务名英中翻译表（来自 flowcld SCM）。
     * @return 英文任务名 → 中文任务名；找不到时返回空表（UI 回退英文）。
     */
    fun loadMissionTranslations(): Map<String, String> {
        val obj = bestJson(Dataset.MISSION_TRANSLATIONS).optJSONObject("missions") ?: return emptyMap()
        val map = HashMap<String, String>(obj.length())
        for (key in obj.keys()) {
            val v = obj.optString(key)
            if (v.isNotBlank()) map[key] = v
        }
        return map
    }

    /** 加载槽位名翻译（slot 英文 -> 中文）。 */
    fun loadSlotTranslations(): Map<String, String> {
        val obj = bestJson(Dataset.SLOT_TRANSLATIONS).optJSONObject("slots") ?: return emptyMap()
        val map = HashMap<String, String>(obj.length())
        for (key in obj.keys()) {
            val v = obj.optString(key)
            if (v.isNotBlank()) map[key.uppercase()] = v
        }
        return map
    }

    /**
     * 加载蓝图配方数据,返回 { 英文名 -> qualityInfo } 的解析结果。
     * qualityInfo 含 availableProperties / modifierRanges(min/max)等线索。
     */
    fun loadBlueprintHints(): Map<String, JSONObject> {
        val root = bestJson(Dataset.BLUEPRINTS)
        val obj = root.optJSONObject("blueprints") ?: JSONObject()
        val map = HashMap<String, JSONObject>()
        for (key in obj.keys()) {
            obj.optJSONObject(key)?.let { map[key] = it }
        }
        return map
    }

    /** 按物品英文名取强类型配方线索;无配方返回 null。 */
    fun loadBlueprintHintFor(itemNameEn: String): BlueprintHint? =
        loadBlueprintHints()[itemNameEn]?.let { BlueprintHint.fromJson(itemNameEn, it) }

    /**
     * 加载全部奖励任务（按 missionGuid 去重），用于图鉴「任务」板块浏览。
     * @return 任务列表；无数据返回空表。
     */
    fun loadAllMissions(): List<RewardMission> {
        val root = bestJson(Dataset.MISSIONS)
        val obj = root.optJSONObject("missions") ?: return emptyList()
        val types = missionTypesOf(root)
        val result = ArrayList<RewardMission>(obj.length())
        for (guid in obj.keys()) {
            obj.optJSONObject(guid)?.let { result += RewardMission.fromJson(guid, it, types) }
        }
        return result
    }

    /** 按 missionGuid 取单个任务详情;无则返回 null。 */
    fun loadMissionByGuid(guid: String): RewardMission? {
        val root = bestJson(Dataset.MISSIONS)
        val m = root.optJSONObject("missions")?.optJSONObject(guid) ?: return null
        return RewardMission.fromJson(guid, m, missionTypesOf(root))
    }

    /**
     * 按蓝图英文名取其全部奖励任务;无对应蓝图返回空表。
     * 匹配键为蓝图英文名(blueprintName),与 sccraft 物品英文名一致(约 635/670 对齐)。
     */
    fun loadMissionsForBlueprint(nameEn: String): List<RewardMission> {
        val root = bestJson(Dataset.MISSIONS)
        val missionsObj = root.optJSONObject("missions") ?: return emptyList()
        val guids = root.optJSONObject("blueprintMissions")?.optJSONArray(nameEn) ?: return emptyList()
        val types = missionTypesOf(root)
        val result = ArrayList<RewardMission>(guids.length())
        for (i in 0 until guids.length()) {
            val guid = guids.optString(i)
            missionsObj.optJSONObject(guid)?.let { result += RewardMission.fromJson(guid, it, types) }
        }
        return result
    }

    private fun missionTypesOf(root: JSONObject): Map<String, String> {
        val obj = root.optJSONObject("missionTypes") ?: return emptyMap()
        val map = HashMap<String, String>(obj.length())
        for (k in obj.keys()) obj.optString(k).takeIf { it.isNotBlank() }?.let { map[k] = it }
        return map
    }

    /**
     * 加载 sc-craft.tools 格式的完整蓝图数据（含全曲线品质修正 + 任务列表）。
     * @param nameEn 物品英文名，与蓝图 JSON 中的键一致
     */
    fun loadScCraftBlueprint(nameEn: String): ScCraftBlueprint? {
        val obj = bestJson(Dataset.SCCRAFT).optJSONObject("blueprints") ?: return null
        return obj.optJSONObject(nameEn)?.let { ScCraftBlueprint.fromJson(nameEn, it) }
    }

    /**
     * 按物品英文名取基准属性值（来自 star-citizen.wiki），key 为 statLocKey。
     * sc-craft 只给品质修正百分比，这里补上绝对基准值（如单发伤害 165、射速 30），
     * 用于「基础 / 强化 / 变化」三列显示。无基准的属性（后坐力、护甲血量/减伤）不在此表中。
     * @return statLocKey → 基准值；无数据返回空表。
     */
    fun loadItemBaseStatsFor(nameEn: String): Map<String, Double> {
        val obj = bestJson(Dataset.ITEM_STATS).optJSONObject("stats") ?: return emptyMap()
        val item = obj.optJSONObject(nameEn) ?: return emptyMap()
        val map = HashMap<String, Double>(item.length())
        for (key in item.keys()) {
            map[key] = item.optDouble(key)
        }
        return map
    }

    /**
     * 一次性读取全量索引（JSON 只解析一次），用于列表页快速渲染。
     * 每条只含轻量字段；详情页再按名称加载完整数据。
     */
    fun loadScCraftIndex(): List<ScCraftIndexEntry> {
        val root = bestJson(Dataset.SCCRAFT)
        val obj = root.optJSONObject("blueprints") ?: return emptyList()
        val result = ArrayList<ScCraftIndexEntry>(obj.length())
        for (nameEn in obj.keys()) {
            val bp = obj.optJSONObject(nameEn) ?: continue
            val mats = LinkedHashSet<String>()
            bp.optJSONArray("slots")?.let { slots ->
                for (i in 0 until slots.length()) {
                    val slot = slots.optJSONObject(i) ?: continue
                    slot.optString("material").takeIf { it.isNotBlank() }?.let { mats += it }
                    slot.optJSONArray("allOptions")?.let { opts ->
                        for (j in 0 until opts.length()) {
                            opts.optJSONObject(j)?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let { mats += it }
                        }
                    }
                }
            }
            result += ScCraftIndexEntry(
                nameEn = nameEn,
                category = bp.optString("category"),
                craftTimeSeconds = bp.optInt("craftTimeSeconds", 0),
                missionCount = bp.optJSONArray("missions")?.length() ?: 0,
                materials = mats.toList(),
            )
        }
        return result
    }

    /** 当前生效的数据版本(用于 UI 展示 / 调试)。 */
    fun currentVersion(dataset: Dataset): Int = versionOf(bestJson(dataset))

    fun gameVersion(dataset: Dataset): String? =
        bestJson(dataset).optString("gameVersion").takeIf { it.isNotBlank() }

    // ---- 远程热更新 ----

    /**
     * 从远程逐个数据集检查更新:远程 version 更高才下载并原子写入缓存。
     * @return 实际更新的数据集列表;远程未配置 / 失败时返回空,不抛异常。
     */
    fun refreshFromRemote(): List<Dataset> {
        if (RemoteConfig.baseUrl.isBlank()) {
            debug("远程地址未配置,跳过热更新")
            return emptyList()
        }
        val updated = mutableListOf<Dataset>()
        for (ds in Dataset.entries) {
            runCatching { refreshOne(ds) }
                .onSuccess { if (it) updated += ds }
                .onFailure { debug("更新 ${ds.fileName} 失败: ${it.message}") }
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
            debug("${ds.fileName} 远程版本 $remoteVersion <= 本地 $localVersion,无需更新")
            return false
        }
        // 原子写入:先写临时文件再 rename
        val target = cacheFile(ds)
        val tmp = File(target.parentFile, ds.fileName + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text); tmp.delete()
        }
        debug("${ds.fileName} 已更新到版本 $remoteVersion")
        return true
    }

    // ---- 内部:取「缓存 vs assets」中版本较高者 ----

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

    private fun versionOf(json: JSONObject): Int = json.optInt("version", 0)

    private fun cacheFile(ds: Dataset): File =
        File(context.filesDir, "blueprint/${ds.fileName}").apply { parentFile?.mkdirs() }

    private fun readCache(ds: Dataset): JSONObject? = runCatching {
        val f = cacheFile(ds)
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    private fun readAsset(ds: Dataset): JSONObject? = runCatching {
        context.assets.open("blueprint/${ds.fileName}")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
    }.getOrNull()

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

    /** 远程数据托管配置 —— TODO: 部署后填入你自己的 CDN / GitHub raw 地址。 */
    object RemoteConfig {
        /**
         * 数据文件所在目录的基址,文件名与 assets 一致。例如:
         *   "https://raw.githubusercontent.com/<you>/<repo>/main/blueprint"
         * 留空则完全使用本地数据,不发起网络请求。
         */
        var baseUrl: String = ""
    }

    companion object {
        private const val TAG = "BlueprintData"
    }
}
