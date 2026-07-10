package com.euedrc.bugsc.agent

import android.content.Context
import com.euedrc.bugsc.HangarTimerEngine
import com.euedrc.bugsc.HangarTimerSyncSources
import com.euedrc.bugsc.InventoryCacheCodec
import com.euedrc.bugsc.InventoryDisplay
import com.euedrc.bugsc.InventoryDisplayFormatter
import com.euedrc.bugsc.InventoryItem
import com.euedrc.bugsc.RsiStatusCache
import com.euedrc.bugsc.RsiStatusClient
import com.euedrc.bugsc.ServiceStatusLevel
import com.euedrc.bugsc.ToolHeaderStatus
import com.euedrc.bugsc.wb.WbRepository
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppToolSyncResult(
    val success: Boolean,
    val message: String,
)

interface DailyWbProvider {
    fun generatedAt(): String?
    fun loadItems(): List<WbRepository.WbItem>
}

interface SyncableDailyWbProvider : DailyWbProvider {
    fun sync(): AppToolSyncResult
}

data class HangarTimerSnapshot(
    val phaseLabel: String,
    val isOpen: Boolean,
    val lights: List<String>,
    val remainingSeconds: Long,
    val nextOpenAtSeconds: Long,
    val source: String,
    val anchorAtSeconds: Long,
)

interface HangarTimerSnapshotProvider {
    fun snapshot(): HangarTimerSnapshot
}

interface SyncableHangarTimerSnapshotProvider : HangarTimerSnapshotProvider {
    fun sync(): AppToolSyncResult
}

interface RsiInventoryProvider {
    fun lastSync(): String?
    fun loadItems(): List<InventoryItem>
    fun shipAliases(): Map<String, String>
}

data class RsiServerStatusSnapshot(
    val status: ToolHeaderStatus,
    val updatedAt: Long,
    val source: String,
)

interface RsiServerStatusProvider {
    fun snapshot(): RsiServerStatusSnapshot
}

interface SyncableRsiServerStatusProvider : RsiServerStatusProvider {
    fun sync(): AppToolSyncResult
}

object AppUtilityTools {
    fun create(context: Context): List<AgentTool> = create(
        dailyWbProvider = AndroidDailyWbProvider(context.applicationContext),
        hangarTimerProvider = AndroidHangarTimerSnapshotProvider(context.applicationContext),
        rsiInventoryProvider = AndroidRsiInventoryProvider(context.applicationContext),
        rsiServerStatusProvider = AndroidRsiServerStatusProvider(context.applicationContext),
    )

    fun create(
        dailyWbProvider: DailyWbProvider,
        hangarTimerProvider: HangarTimerSnapshotProvider,
        rsiInventoryProvider: RsiInventoryProvider = EmptyRsiInventoryProvider,
        rsiServerStatusProvider: RsiServerStatusProvider = DefaultRsiServerStatusProvider,
    ): List<AgentTool> = listOf(
        DailyWbTool(dailyWbProvider),
        HangarTimerTool(hangarTimerProvider),
        RsiInventoryTool(rsiInventoryProvider),
        RsiServerStatusTool(rsiServerStatusProvider),
        AppCapabilitiesTool(),
    )
}

class DailyWbTool(
    private val provider: DailyWbProvider,
) : AgentTool {
    override val name: String = "get_daily_wb"
    override val description: String = "每日 WB / Warbond 限时折扣船查询，返回当前 WB 价格、原价和更新时间"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("query", "可选：船名关键词，中英文均可；为空则返回当前列表", required = false),
        AgentToolParameter("limit", "可选：最多返回条数，默认 8", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val syncResult = (provider as? SyncableDailyWbProvider)?.sync()
        val query = call.args["query"].orEmpty().trim()
        val limit = call.args["limit"].orEmpty().toIntOrNull()?.coerceIn(1, 10) ?: 8
        val items = provider.loadItems()
            .filter { item -> query.isBlank() || item.matches(query) }
            .take(limit)
        if (items.isEmpty()) {
            val label = if (query.isBlank()) "每日 WB 暂无数据" else "每日 WB 未命中：$query"
            return AgentToolResult(
                call = call,
                summary = label,
                facts = emptyList(),
                sources = listOf(AgentSource("每日 WB 数据", "local")),
                confidence = 0f,
            )
        }
        val generatedAt = provider.generatedAt().orEmpty()
        return AgentToolResult(
            call = call,
            summary = buildString {
                syncResult?.let { appendLine(it.message) }
                if (generatedAt.isNotBlank()) appendLine("更新于：$generatedAt")
                items.forEach { item -> appendLine(item.summaryLine()) }
            }.trim(),
            facts = buildList {
                syncResult?.let { add(AgentFact("同步状态", it.message)) }
                if (generatedAt.isNotBlank()) add(AgentFact("更新于", generatedAt))
                items.forEach { item ->
                    add(AgentFact("每日 WB", item.displayName))
                    item.warbondPrice?.let { add(AgentFact("WB价", "${item.currency} ${formatMoney(it)}")) }
                    item.standardPrice?.let { add(AgentFact("原价", "${item.currency} ${formatMoney(it)}")) }
                    item.discount()?.let { add(AgentFact("优惠", "${item.currency} ${formatMoney(it)}")) }
                    item.url?.let { add(AgentFact("链接", it)) }
                }
            },
            sources = listOf(AgentSource("每日 WB 数据", "local", generatedAt)),
            confidence = if (syncResult?.success == false) 0.58f else 0.74f,
        )
    }

    private fun WbRepository.WbItem.matches(query: String): Boolean {
        val compactQuery = AgentAliasNormalizer.compact(query)
        return listOf(displayName, nameEn, nameZh.orEmpty()).any { value ->
            val compactValue = AgentAliasNormalizer.compact(value)
            compactValue.contains(compactQuery) || compactQuery.contains(compactValue)
        }
    }

    private fun WbRepository.WbItem.summaryLine(): String {
        val wb = warbondPrice?.let { "WB $currency ${formatMoney(it)}" } ?: "WB 价格未知"
        val standard = standardPrice?.let { "原价 $currency ${formatMoney(it)}" }
        val discount = discount()?.let { "省 $currency ${formatMoney(it)}" }
        return listOf(displayName, wb, standard, discount).filterNotNull().joinToString("，")
    }

    private fun WbRepository.WbItem.discount(): Double? {
        val wb = warbondPrice ?: return null
        val standard = standardPrice ?: return null
        return (standard - wb).takeIf { it > 0.0 }
    }
}

class HangarTimerTool(
    private val provider: HangarTimerSnapshotProvider,
) : AgentTool {
    override val name: String = "get_hangar_timer"
    override val description: String = "行政机库倒计时查询，返回当前开启/关闭状态、灯状态、剩余时间和下一次开启时间"
    override val parameters: List<AgentToolParameter> = emptyList()

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val syncResult = (provider as? SyncableHangarTimerSnapshotProvider)?.sync()
        val snapshot = provider.snapshot()
        val remaining = formatDuration(snapshot.remainingSeconds)
        val nextOpen = formatTimestamp(snapshot.nextOpenAtSeconds)
        return AgentToolResult(
            call = call,
            summary = buildString {
                syncResult?.let { append("${it.message}；") }
                append("${snapshot.phaseLabel}，剩余 $remaining，下一次开启：$nextOpen，来源：${snapshot.source}")
            },
            facts = buildList {
                syncResult?.let { add(AgentFact("同步状态", it.message)) }
                add(AgentFact("状态", snapshot.phaseLabel))
                add(AgentFact("是否开启", if (snapshot.isOpen) "是" else "否"))
                add(AgentFact("剩余时间", remaining))
                add(AgentFact("下一次开启", nextOpen))
                add(AgentFact("灯状态", snapshot.lights.joinToString(",")))
                add(AgentFact("来源", snapshot.source))
            },
            sources = listOf(AgentSource("行政机库计时器", "local", snapshot.anchorAtSeconds.toString())),
            confidence = if (syncResult?.success == false) 0.55f else 0.76f,
        )
    }
}

class RsiInventoryTool(
    private val provider: RsiInventoryProvider,
) : AgentTool {
    override val name: String = "get_rsi_inventory"
    override val description: String =
        "读取 App 本地缓存的 RSI 机库库存，返回 CCU/WB/标准/皮肤/整船/包、价格、保险和页码；不会重新登录或上传 token"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("query", "可选：库存关键词，中英文均可，例如 瑞伦、Railen、WB、CCU、陆龟", required = false),
        AgentToolParameter("type", "可选：all、ccu、ship、package、paint、item，默认 all", required = false),
        AgentToolParameter("limit", "可选：最多返回条数，默认 20，最大 80", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val query = call.args["query"].orEmpty().trim()
        val type = call.args["type"].orEmpty().ifBlank { "all" }.lowercase(Locale.US)
        val limit = call.args["limit"].orEmpty().toIntOrNull()?.coerceIn(1, 80) ?: 20
        val aliases = provider.shipAliases()
        val allItems = provider.loadItems()
        if (allItems.isEmpty()) {
            return AgentToolResult(
                call = call,
                summary = "RSI 机库库存暂无本地缓存。请先进入“RSI 机库库存”页面登录并刷新库存。",
                facts = emptyList(),
                sources = listOf(AgentSource("RSI 机库库存缓存", "local")),
                confidence = 0f,
            )
        }
        val prepared = allItems.map { item -> item to InventoryDisplayFormatter.format(item, aliases) }
        val filtered = prepared
            .filter { (_, display) -> type == "all" || display.matchesType(type) }
            .filter { (item, display) -> query.isBlank() || item.matchesQuery(display, query) }
        val visible = filtered.take(limit)
        val lastSync = provider.lastSync().orEmpty()
        if (visible.isEmpty()) {
            return AgentToolResult(
                call = call,
                summary = buildString {
                    append("RSI 机库库存未命中")
                    if (query.isNotBlank()) append("：").append(query)
                    append("。本地缓存共 ${allItems.size} 项")
                    if (lastSync.isNotBlank()) append("，最近同步：").append(lastSync)
                },
                facts = listOf(
                    AgentFact("库存总数", allItems.size.toString()),
                    AgentFact("最近同步", lastSync.ifBlank { "未知" }),
                ),
                sources = listOf(AgentSource("RSI 机库库存缓存", "local", lastSync)),
                confidence = 0.35f,
            )
        }
        return AgentToolResult(
            call = call,
            summary = buildString {
                appendLine("RSI 机库库存：共 ${allItems.size} 项，命中 ${filtered.size} 项")
                if (lastSync.isNotBlank()) appendLine("最近同步：$lastSync")
                visible.forEach { (_, display) -> appendLine(display.summaryLine()) }
                if (filtered.size > visible.size) appendLine("还有 ${filtered.size - visible.size} 项未显示，可缩小 query 或提高 limit。")
            }.trim(),
            facts = buildList {
                add(AgentFact("库存总数", allItems.size.toString()))
                if (lastSync.isNotBlank()) add(AgentFact("最近同步", lastSync))
                visible.forEach { (_, display) ->
                    add(AgentFact("库存", display.summaryLine()))
                    if (display.tags.contains("CCU")) add(AgentFact("CCU", display.title))
                    if (display.tags.contains("WB")) add(AgentFact("WB", display.title))
                }
            },
            sources = listOf(AgentSource("RSI 机库库存缓存", "local", lastSync)),
            confidence = 0.72f,
        )
    }

    private fun InventoryDisplay.matchesType(type: String): Boolean = when (type) {
        "ccu" -> tags.contains("CCU")
        "ship" -> tags.contains("整船/包")
        "package", "pack" -> tags.contains("包")
        "paint", "skin" -> tags.contains("皮肤")
        "item" -> tags.contains("物品")
        "wb", "warbond" -> tags.contains("WB")
        else -> true
    }

    private fun InventoryItem.matchesQuery(display: com.euedrc.bugsc.InventoryDisplay, query: String): Boolean {
        val compactQuery = AgentAliasNormalizer.compact(query)
        val lowerQuery = query.lowercase(Locale.US)
        return buildList {
            add(name)
            add(contains)
            add(display.title)
            add(display.subtitle)
            add(display.detail)
            add(display.tags.joinToString(" "))
        }.any { value ->
            val compactValue = AgentAliasNormalizer.compact(value)
            compactValue.contains(compactQuery) ||
                compactQuery.contains(compactValue) ||
                value.lowercase(Locale.US).contains(lowerQuery)
        }
    }

    private fun com.euedrc.bugsc.InventoryDisplay.summaryLine(): String {
        val subtitleText = subtitle.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        val tagsText = tags.joinToString(" / ")
        return "- $title$subtitleText：$tagsText${detail.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()}"
    }
}

class RsiServerStatusTool(
    private val provider: RsiServerStatusProvider,
) : AgentTool {
    override val name: String = "get_rsi_server_status"
    override val description: String =
        "查询 RSI / 星际公民服务器状态，返回 Platform、Persistent Universe 和 Arena Commander 当前状态"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("service", "可选：platform、pu、arena；为空返回全部", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val syncResult = (provider as? SyncableRsiServerStatusProvider)?.sync()
        val snapshot = provider.snapshot()
        val service = call.args["service"].orEmpty().trim().lowercase(Locale.US)
        val rows = snapshot.status.rows().filter { (key, _) ->
            service.isBlank() || key == service || service == "all"
        }.ifEmpty { snapshot.status.rows() }
        val updatedAt = formatMillis(snapshot.updatedAt)
        return AgentToolResult(
            call = call,
            summary = buildString {
                syncResult?.let { appendLine(it.message) }
                appendLine("RSI 服务器状态：")
                rows.forEach { (_, row) -> appendLine("${row.name}：${row.level.label()}") }
                if (updatedAt.isNotBlank()) appendLine("更新于：$updatedAt")
                append("来源：${snapshot.source}")
            }.trim(),
            facts = buildList {
                syncResult?.let { add(AgentFact("同步状态", it.message)) }
                rows.forEach { (_, row) -> add(AgentFact(row.name, row.level.label())) }
                if (updatedAt.isNotBlank()) add(AgentFact("更新于", updatedAt))
                add(AgentFact("来源", snapshot.source))
            },
            sources = listOf(AgentSource("RSI Status Page", "https://status.robertsspaceindustries.com/", updatedAt)),
            confidence = when {
                syncResult?.success == true -> 0.78f
                syncResult?.success == false -> 0.52f
                snapshot.source == "default" -> 0.45f
                else -> 0.68f
            },
        )
    }

    private data class StatusRow(val name: String, val level: ServiceStatusLevel)

    private fun ToolHeaderStatus.rows(): List<Pair<String, StatusRow>> = listOf(
        "platform" to StatusRow("Platform", platform),
        "pu" to StatusRow("Persistent Universe", persistentUniverse),
        "persistentuniverse" to StatusRow("Persistent Universe", persistentUniverse),
        "arena" to StatusRow("Arena Commander", arenaCommander),
        "arenacommander" to StatusRow("Arena Commander", arenaCommander),
    ).distinctBy { it.second.name }

    private fun ServiceStatusLevel.label(): String = when (this) {
        ServiceStatusLevel.OPERATIONAL -> "正常"
        ServiceStatusLevel.DEGRADED -> "降级"
        ServiceStatusLevel.OUTAGE -> "停机"
    }
}

class AppCapabilitiesTool : AgentTool {
    override val name: String = "list_app_capabilities"
    override val description: String = "列出 SCMobiGlas 当前 App 能力和对应可用工具，帮助判断是否能用工具回答"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("query", "可选：按能力名称或关键词过滤", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val query = call.args["query"].orEmpty().trim()
        val visible = APP_CAPABILITIES.filter { capability ->
            query.isBlank() || capability.matches(query)
        }
        val selected = visible.ifEmpty { APP_CAPABILITIES }
        return AgentToolResult(
            call = call,
            summary = selected.joinToString("\n") { it.summaryLine() },
            facts = selected.map { AgentFact("能力", it.summaryLine()) },
            sources = listOf(AgentSource("SCMobiGlas 功能目录", "local")),
            confidence = 0.7f,
        )
    }

    private fun AppCapability.matches(query: String): Boolean {
        val compactQuery = AgentAliasNormalizer.compact(query)
        return listOf(name, tool, description).any { value ->
            val compactValue = AgentAliasNormalizer.compact(value)
            compactValue.contains(compactQuery) || compactQuery.contains(compactValue)
        }
    }

    private fun AppCapability.summaryLine(): String =
        "$name：$description；工具：$tool；安全级别：$safety"
}

private class AndroidDailyWbProvider(context: Context) : SyncableDailyWbProvider {
    private val repository = WbRepository(context)

    override fun sync(): AppToolSyncResult =
        runCatching {
            repository.refreshFromRemote()
            AppToolSyncResult(success = true, message = "已同步每日 WB")
        }.getOrElse { error ->
            AppToolSyncResult(
                success = false,
                message = "每日 WB 同步失败，使用本地缓存：${error.message ?: error::class.java.simpleName}",
            )
        }

    override fun generatedAt(): String? = repository.generatedAt()

    override fun loadItems(): List<WbRepository.WbItem> = repository.loadWbItems()
}

private class AndroidHangarTimerSnapshotProvider(
    private val context: Context,
) : SyncableHangarTimerSnapshotProvider {
    override fun sync(): AppToolSyncResult =
        runCatching {
            val now = nowSeconds()
            val script = fetchText(XYXYLL_APP_JS_URL)
            val candidate = HangarTimerSyncSources.buildAuthoritativeCandidate(script)
            val selection = HangarTimerSyncSources.chooseClosestToNow(now, listOf(candidate))
            persistAnchor(selection.anchorLights, selection.projectedAnchorSeconds)
            AppToolSyncResult(success = true, message = "已同步行政机库：${selection.name}")
        }.getOrElse { error ->
            AppToolSyncResult(
                success = false,
                message = "行政机库同步失败，使用当前本地状态：${error.message ?: error::class.java.simpleName}",
            )
        }

    override fun snapshot(): HangarTimerSnapshot {
        val prefs = context.getSharedPreferences(HANGAR_PREFS, Context.MODE_PRIVATE)
        val cachedLights = prefs.getString(KEY_LIGHTS, null)
        val cachedAt = prefs.getLong(KEY_CONFIRMED_AT, 0L)
        val cachedVersion = prefs.getInt(KEY_RULE_VERSION, 0)
        val parsedLights = cachedLights
            ?.split(",")
            ?.takeIf { it.size == 5 && it.all { light -> light in setOf("red", "green", "gray") } }
        val hasConfirmed = parsedLights != null && cachedAt > 0L && cachedVersion == RULE_VERSION
        val anchorLights = if (hasConfirmed) parsedLights.orEmpty() else DEFAULT_LIGHTS
        val anchorAt = if (hasConfirmed) cachedAt else nowSeconds()
        val now = nowSeconds()
        val state = HangarTimerEngine.computeStateByElapsed(
            anchors = anchorLights,
            elapsed = maxOf(0L, now - anchorAt),
            redToGreenSeconds = RED_TO_GREEN_SECONDS,
            greenToGraySeconds = GREEN_TO_GRAY_SECONDS,
            allGrayHoldSeconds = ALL_GRAY_HOLD_SECONDS,
            defaultLights = DEFAULT_LIGHTS,
        )
        val nextOpenAt = HangarTimerEngine.nextOpenAtSeconds(
            anchors = anchorLights,
            anchorAt = anchorAt,
            nowSeconds = now,
            redToGreenSeconds = RED_TO_GREEN_SECONDS,
            greenToGraySeconds = GREEN_TO_GRAY_SECONDS,
            allGrayHoldSeconds = ALL_GRAY_HOLD_SECONDS,
            defaultLights = DEFAULT_LIGHTS,
        )
        val isOpen = state.phase != 'A'
        return HangarTimerSnapshot(
            phaseLabel = if (isOpen) "机库开启" else "机库关闭",
            isOpen = isOpen,
            lights = state.lights,
            remainingSeconds = state.remainingSeconds,
            nextOpenAtSeconds = nextOpenAt,
            source = if (hasConfirmed) "已校准" else "默认基准",
            anchorAtSeconds = anchorAt,
        )
    }

    private fun persistAnchor(lights: List<String>, at: Long) {
        context.getSharedPreferences(HANGAR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIGHTS, lights.joinToString(","))
            .putLong(KEY_CONFIRMED_AT, at)
            .putInt(KEY_RULE_VERSION, RULE_VERSION)
            .apply()
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "*/*")
            if (conn.responseCode != 200) throw IllegalStateException("同步源返回异常(${conn.responseCode})")
            return BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}

private class AndroidRsiInventoryProvider(
    private val context: Context,
) : RsiInventoryProvider {
    private val prefs by lazy { context.getSharedPreferences(INVENTORY_PREFS, Context.MODE_PRIVATE) }

    override fun lastSync(): String? = prefs.getString(KEY_LAST_SYNC, "")?.takeIf { it.isNotBlank() }

    override fun loadItems(): List<InventoryItem> =
        InventoryCacheCodec.decodeItems(prefs.getString(KEY_ITEMS_CACHE, "") ?: "")

    override fun shipAliases(): Map<String, String> = runCatching {
        val root = context.assets.open("shipfit/zh_aliases.json")
            .bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
        val ships = root.optJSONObject("ships") ?: return@runCatching emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in ships.keys()) {
            val value = ships.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        out
    }.getOrDefault(emptyMap())
}

private class AndroidRsiServerStatusProvider(
    context: Context,
) : SyncableRsiServerStatusProvider {
    private val cache = RsiStatusCache(context)
    private val client = RsiStatusClient()
    private var latest: RsiServerStatusSnapshot? = cache.load()?.let { cached ->
        RsiServerStatusSnapshot(cached.status, cached.updatedAt, "cache")
    }

    override fun sync(): AppToolSyncResult =
        runCatching {
            val status = client.fetchStatus()
            val updatedAt = System.currentTimeMillis()
            cache.save(status, updatedAt)
            latest = RsiServerStatusSnapshot(status, updatedAt, "remote")
            AppToolSyncResult(success = true, message = "已同步 RSI 状态")
        }.getOrElse { error ->
            AppToolSyncResult(
                success = false,
                message = "RSI 状态同步失败，使用本地缓存：${error.message ?: error::class.java.simpleName}",
            )
        }

    override fun snapshot(): RsiServerStatusSnapshot =
        latest ?: DefaultRsiServerStatusProvider.snapshot()
}

private object EmptyRsiInventoryProvider : RsiInventoryProvider {
    override fun lastSync(): String? = null
    override fun loadItems(): List<InventoryItem> = emptyList()
    override fun shipAliases(): Map<String, String> = emptyMap()
}

private object DefaultRsiServerStatusProvider : RsiServerStatusProvider {
    override fun snapshot(): RsiServerStatusSnapshot =
        RsiServerStatusSnapshot(ToolHeaderStatus(), updatedAt = 0L, source = "default")
}

private data class AppCapability(
    val name: String,
    val tool: String,
    val description: String,
    val safety: String,
)

private val APP_CAPABILITIES = listOf(
    AppCapability("飞船资料", "search_ship", "查询船只中文名、英文名、尺寸、货仓、槽位等本地资料", "只读"),
    AppCapability("线上飞船资料", "search_online_ship / get_online_ship_detail", "查询 SCAPI 线上飞船、挂点和完整详情", "只读"),
    AppCapability("矿物资料", "search_mining", "查询矿物属性和常见地点", "只读"),
    AppCapability("蓝图资料", "search_blueprint / search_scm_blueprint", "查询本地与 SCM 蓝图、材料、制作时间和来源", "只读"),
    AppCapability("任务资料", "search_mission", "查询任务、奖励、阵营和蓝图来源", "只读"),
    AppCapability("维科洛兑换", "search_wikelo", "查询 Wikelo 兑换材料和奖励", "只读"),
    AppCapability("SCM 市场", "search_market", "查询出售/求购挂单和价格", "只读"),
    AppCapability("SCM 我的挂单", "list_my_orders", "查询当前登录用户自己的出售/求购挂单", "登录后只读"),
    AppCapability("SCM 我的交易", "list_my_market_activity", "查询我的挂单和我买入/卖出的交易记录", "登录后只读"),
    AppCapability("SCM 物品", "search_scm_item", "查询 SCM 物品和物品 ID", "只读"),
    AppCapability("SCM 订单草稿", "draft_scm_order", "整理出售/求购订单草稿，提交必须用户确认", "需确认"),
    AppCapability("SCM 签到", "scm_sign_in", "查询签到状态或执行每日签到", "登录后可执行"),
    AppCapability("每日 WB", "get_daily_wb", "查询 RSI 官网 Warbond 限时折扣船", "只读"),
    AppCapability("行政机库", "get_hangar_timer", "查询行政机库开启/关闭倒计时和下一次开启时间", "只读"),
    AppCapability("RSI 机库库存", "get_rsi_inventory", "读取 App 本地缓存的 RSI 机库库存，支持 CCU/WB/皮肤/整船/包过滤", "登录后只读"),
    AppCapability("RSI 服务器状态", "get_rsi_server_status", "查询 RSI 状态页里的 Platform、PU 和 Arena Commander 状态", "只读"),
    AppCapability("Issue Council", "无直接工具", "App 可打开 Issue Council 页面和提交 Bug 页面", "用户操作"),
    AppCapability("角色修复", "无直接工具", "App 可打开 RSI 角色修复页面，需要 RSI 登录", "用户操作"),
    AppCapability("SCM 聊天/交易", "无直接工具", "App 可查看会话、交易列表和交易详情，需要 SCM 登录", "登录后用户操作"),
)

private const val HANGAR_PREFS = "hangar_timer"
private const val INVENTORY_PREFS = "inventory"
private const val RULE_VERSION = 1
private const val RED_TO_GREEN_SECONDS = 24L * 60L
private const val GREEN_TO_GRAY_SECONDS = 12L * 60L
private const val ALL_GRAY_HOLD_SECONDS = 5L * 60L
private val DEFAULT_LIGHTS = listOf("red", "red", "red", "red", "red")
private const val KEY_LIGHTS = "confirmedLights"
private const val KEY_CONFIRMED_AT = "confirmedAt"
private const val KEY_RULE_VERSION = "ruleVersion"
private const val KEY_ITEMS_CACHE = "inventoryItemsCache"
private const val KEY_LAST_SYNC = "inventoryLastSync"
private const val XYXYLL_APP_JS_URL = "https://exec.xyxyll.com/app.js"

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

private fun formatTimestamp(seconds: Long): String {
    if (seconds <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000L))
}

private fun formatMillis(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

private fun formatMoney(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
