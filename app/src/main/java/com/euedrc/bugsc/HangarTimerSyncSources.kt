package com.euedrc.bugsc

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

object HangarTimerSyncSources {

    data class SyncCandidate(
        val name: String,
        val anchorSeconds: Long,
        val cycleSeconds: Double,
        val projectedAnchorSeconds: Long = anchorSeconds
    )

    private val xyxyllOpenTimePattern = Pattern.compile(
        """INITIAL_OPEN_TIME\s*=\s*new Date\('([^']+)'\)"""
    )
    private val xyxyllOnlineMinutesPattern = Pattern.compile("""DESIGN_ONLINE_MIN\s*=\s*(\d+)""")
    private val xyxyllOfflineMinutesPattern = Pattern.compile("""DESIGN_OFFLINE_MIN\s*=\s*(\d+)""")
    private val xyxyllCycleDriftPattern = Pattern.compile("""CYCLE_DRIFT_MS\s*=\s*(\d+)""")

    fun parseExecTimerAnchorSeconds(body: String): Long {
        val parsed = JSONObject(body)
        val raw = parsed.optLong("startTime", -1L)
        require(raw > 0) { "startTime 字段无效" }
        return if (raw > 1_000_000_000_000L) raw / 1000 else raw
    }

    fun parseExecTimerCycleSeconds(body: String): Double {
        val parsed = JSONObject(body)
        val settings = parsed.optJSONObject("adminSettings")
            ?: throw IllegalArgumentException("adminSettings 字段无效")
        val powerUpMinutes = settings.optDouble("powerUpDuration", Double.NaN)
        val powerDownMinutes = settings.optDouble("powerDownDuration", Double.NaN)
        val cooldownMinutes = settings.optDouble("cooldownDuration", Double.NaN)
        require(!powerUpMinutes.isNaN() && !powerDownMinutes.isNaN() && !cooldownMinutes.isNaN()) {
            "adminSettings 时长字段无效"
        }
        return powerUpMinutes * 5 * 60 + powerDownMinutes * 5 * 60 + cooldownMinutes * 60
    }

    fun parseXyxyllAnchorSeconds(script: String): Long {
        val matcher = xyxyllOpenTimePattern.matcher(script)
        require(matcher.find()) { "未找到 INITIAL_OPEN_TIME" }
        val openAt = parseIsoMillis(matcher.group(1) ?: "")
        require(openAt > 0) { "INITIAL_OPEN_TIME 无效" }
        val closeDurationMs = parseXyxyllCloseDurationMillis(script)
        return ((openAt - closeDurationMs) / 1000L)
    }

    fun parseXyxyllCycleSeconds(script: String): Double {
        return parseXyxyllCycleDurationMillis(script) / 1000.0
    }

    fun chooseClosestToNow(nowSeconds: Long, candidates: List<SyncCandidate>): SyncCandidate {
        require(candidates.isNotEmpty()) { "没有可用同步源" }
        return candidates
            .map { candidate ->
                val projected = projectAnchorToCurrentCycle(nowSeconds, candidate.anchorSeconds, candidate.cycleSeconds)
                candidate.copy(projectedAnchorSeconds = projected)
            }
            .maxByOrNull { it.projectedAnchorSeconds }
            ?: throw IllegalArgumentException("没有可用同步源")
    }

    private fun parseIsoMillis(raw: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw)?.time ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseXyxyllCycleDurationMillis(script: String): Long {
        val onlineMinutes = extractLong(script, xyxyllOnlineMinutesPattern, "DESIGN_ONLINE_MIN")
        val offlineMinutes = extractLong(script, xyxyllOfflineMinutesPattern, "DESIGN_OFFLINE_MIN")
        val driftMillis = extractLong(script, xyxyllCycleDriftPattern, "CYCLE_DRIFT_MS")
        return (onlineMinutes + offlineMinutes) * 60_000L + driftMillis
    }

    private fun parseXyxyllCloseDurationMillis(script: String): Long {
        val cycleDurationMs = parseXyxyllCycleDurationMillis(script).toDouble()
        val onlineMinutes = extractLong(script, xyxyllOnlineMinutesPattern, "DESIGN_ONLINE_MIN").toDouble()
        val offlineMinutes = extractLong(script, xyxyllOfflineMinutesPattern, "DESIGN_OFFLINE_MIN").toDouble()
        val designCycleMinutes = onlineMinutes + offlineMinutes
        return (cycleDurationMs * offlineMinutes / designCycleMinutes).toLong()
    }

    private fun extractLong(script: String, pattern: Pattern, label: String): Long {
        val matcher = pattern.matcher(script)
        require(matcher.find()) { "未找到 $label" }
        return matcher.group(1)?.toLongOrNull()
            ?: throw IllegalArgumentException("$label 无效")
    }

    private fun projectAnchorToCurrentCycle(nowSeconds: Long, anchorSeconds: Long, cycleSeconds: Double): Long {
        if (anchorSeconds >= nowSeconds) return anchorSeconds
        val cycles = kotlin.math.floor((nowSeconds - anchorSeconds) / cycleSeconds).toLong()
        return (anchorSeconds + cycles * cycleSeconds).toLong()
    }
}
