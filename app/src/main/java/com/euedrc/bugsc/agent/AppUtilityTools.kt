package com.euedrc.bugsc.agent

import android.content.Context
import com.euedrc.bugsc.HangarTimerEngine
import com.euedrc.bugsc.HangarTimerSyncSources
import com.euedrc.bugsc.wb.WbRepository
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

object AppUtilityTools {
    fun create(context: Context): List<AgentTool> = create(
        dailyWbProvider = AndroidDailyWbProvider(context.applicationContext),
        hangarTimerProvider = AndroidHangarTimerSnapshotProvider(context.applicationContext),
    )

    fun create(
        dailyWbProvider: DailyWbProvider,
        hangarTimerProvider: HangarTimerSnapshotProvider,
    ): List<AgentTool> = listOf(
        DailyWbTool(dailyWbProvider),
        HangarTimerTool(hangarTimerProvider),
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

private data class AppCapability(
    val name: String,
    val tool: String,
    val description: String,
    val safety: String,
)

private val APP_CAPABILITIES = listOf(
    AppCapability("飞船资料", "search_ship", "查询船只中文名、英文名、尺寸、货仓、槽位等本地资料", "只读"),
    AppCapability("矿物资料", "search_mining", "查询矿物属性和常见地点", "只读"),
    AppCapability("蓝图资料", "search_blueprint / search_scm_blueprint", "查询本地与 SCM 蓝图、材料、制作时间和来源", "只读"),
    AppCapability("任务资料", "search_mission", "查询任务、奖励、阵营和蓝图来源", "只读"),
    AppCapability("维科洛兑换", "search_wikelo", "查询 Wikelo 兑换材料和奖励", "只读"),
    AppCapability("SCM 市场", "search_market", "查询出售/求购挂单和价格", "只读"),
    AppCapability("SCM 物品", "search_scm_item", "查询 SCM 物品和物品 ID", "只读"),
    AppCapability("SCM 订单草稿", "draft_scm_order", "整理出售/求购订单草稿，提交必须用户确认", "需确认"),
    AppCapability("每日 WB", "get_daily_wb", "查询 RSI 官网 Warbond 限时折扣船", "只读"),
    AppCapability("行政机库", "get_hangar_timer", "查询行政机库开启/关闭倒计时和下一次开启时间", "只读"),
    AppCapability("RSI 机库库存", "无直接工具", "App 可在页面中读取用户 RSI 机库，需要登录", "登录后只读"),
    AppCapability("Issue Council", "无直接工具", "App 可打开 Issue Council 页面和提交 Bug 页面", "用户操作"),
    AppCapability("角色修复", "无直接工具", "App 可打开 RSI 角色修复页面，需要 RSI 登录", "用户操作"),
    AppCapability("SCM 聊天/交易", "无直接工具", "App 可查看会话、交易列表和交易详情，需要 SCM 登录", "登录后用户操作"),
)

private const val HANGAR_PREFS = "hangar_timer"
private const val RULE_VERSION = 1
private const val RED_TO_GREEN_SECONDS = 24L * 60L
private const val GREEN_TO_GRAY_SECONDS = 12L * 60L
private const val ALL_GRAY_HOLD_SECONDS = 5L * 60L
private val DEFAULT_LIGHTS = listOf("red", "red", "red", "red", "red")
private const val KEY_LIGHTS = "confirmedLights"
private const val KEY_CONFIRMED_AT = "confirmedAt"
private const val KEY_RULE_VERSION = "ruleVersion"
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

private fun formatMoney(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
