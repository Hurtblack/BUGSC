package com.euedrc.bugsc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HangarTimerFragment : Fragment() {

    private val lights = MutableList(5) { "red" }
    private val draftLights = MutableList(5) { "red" }
    private var isEditMode = false
    private var anchorLights = listOf("red", "red", "red", "red", "red")
    private var anchorAt: Long = 0
    private var hasConfirmed = false
    private var confirmedAt: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var tvPhase: TextView
    private lateinit var tvHangarHint: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var digitViews: List<TextView>
    private lateinit var tvHelper: TextView
    private lateinit var tvSyncInfo: TextView
    private lateinit var tvTip: TextView
    private lateinit var lightsContainer: LinearLayout
    private lateinit var lightViews: List<FrameLayout>
    private lateinit var btnMain: Button
    private lateinit var btnSync: Button
    private lateinit var etCalibrationInput: EditText
    private lateinit var btnShareCal: Button
    private lateinit var btnApplyCal: Button
    private lateinit var statusBanner: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_hangar_timer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences("hangar_timer", Context.MODE_PRIVATE)

        statusBanner = view.findViewById(R.id.status_banner)
        tvPhase = view.findViewById(R.id.tv_phase)
        tvHangarHint = view.findViewById(R.id.tv_hangar_hint)
        tvCountdown = view.findViewById(R.id.tv_countdown)
        digitViews = listOf(
            view.findViewById(R.id.digit_0),
            view.findViewById(R.id.digit_1),
            view.findViewById(R.id.digit_2),
            view.findViewById(R.id.digit_3),
            view.findViewById(R.id.digit_4),
            view.findViewById(R.id.digit_5)
        )
        tvHelper = view.findViewById(R.id.tv_helper)
        tvSyncInfo = view.findViewById(R.id.tv_sync_info)
        tvTip = view.findViewById(R.id.tv_tip)
        lightsContainer = view.findViewById(R.id.lights_row)
        btnMain = view.findViewById(R.id.btn_main)
        btnSync = view.findViewById(R.id.btn_sync)
        etCalibrationInput = view.findViewById(R.id.et_calibration_input)
        btnShareCal = view.findViewById(R.id.btn_share_cal)
        btnApplyCal = view.findViewById(R.id.btn_apply_cal)

        lightViews = (0 until 5).map { i ->
            lightsContainer.getChildAt(i) as FrameLayout
        }

        lightViews.forEachIndexed { index, frame ->
            frame.setOnClickListener { onLightTap(index) }
        }

        btnMain.setOnClickListener {
            if (isEditMode) confirmEdit() else startEditMode()
        }
        btnSync.setOnClickListener { syncFromExecTimer() }
        btnShareCal.setOnClickListener { shareCalibration() }
        btnApplyCal.setOnClickListener { applyCalibration() }

        loadAnchor()
    }

    override fun onResume() {
        super.onResume()
        if (!isEditMode) startTimer()
    }

    override fun onPause() {
        super.onPause()
        stopTimer()
    }

    private fun startEditMode() {
        stopTimer()
        isEditMode = true
        for (i in 0 until 5) draftLights[i] = lights[i]
        renderLights(draftLights, editable = true)
        btnMain.text = "确认灯状态"
        tvTip.text = "点击灯切换状态，倒计时与自动变灯已暂停"
    }

    private fun confirmEdit() {
        val confirmed = draftLights.toList()
        if (!validateLights(confirmed)) {
            Toast.makeText(requireContext(), "仅支持全红/全绿/全灰/红绿/绿灰组合", Toast.LENGTH_SHORT).show()
            return
        }

        confirmedAt = System.currentTimeMillis() / 1000
        isEditMode = false
        for (i in 0 until 5) lights[i] = confirmed[i]
        persistAnchor(confirmed, confirmedAt)
        setAnchor(confirmed, confirmedAt, true)
        btnMain.text = "重置灯状态"
        tvTip.text = "自动倒计时与自动变灯进行中"
    }

    private fun onLightTap(index: Int) {
        if (!isEditMode) return
        draftLights[index] = nextColor(draftLights[index])
        renderLights(draftLights, editable = true)
    }

    private fun loadAnchor() {
        val cachedLights = prefs.getString(KEY_LIGHTS, null)
        val cachedAt = prefs.getLong(KEY_CONFIRMED_AT, 0)
        val cachedVersion = prefs.getInt(KEY_RULE_VERSION, 0)

        if (cachedLights != null && cachedAt > 0 && cachedVersion == RULE_VERSION) {
            val parsed = cachedLights.split(",")
            if (parsed.size == 5 && parsed.all { it in setOf("red", "green", "gray") }) {
                setAnchor(parsed, cachedAt, true)
                return
            }
        }

        setAnchor(DEFAULT_LIGHTS, System.currentTimeMillis() / 1000, false)
    }

    private fun setAnchor(anchorLights: List<String>, anchorAt: Long, hasConfirmed: Boolean) {
        stopTimer()
        this.anchorLights = anchorLights.toList()
        this.anchorAt = anchorAt
        this.hasConfirmed = hasConfirmed
        if (hasConfirmed) {
            this.confirmedAt = anchorAt
        }
        refreshByNow()
        if (!isEditMode) startTimer()
    }

    private fun persistAnchor(lights: List<String>, at: Long) {
        prefs.edit()
            .putString(KEY_LIGHTS, lights.joinToString(","))
            .putLong(KEY_CONFIRMED_AT, at)
            .putInt(KEY_RULE_VERSION, RULE_VERSION)
            .apply()
    }

    private fun startTimer() {
        stopTimer()
        val runnable = object : Runnable {
            override fun run() {
                if (!isEditMode) refreshByNow()
                handler.postDelayed(this, 1000)
            }
        }
        timerRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun refreshByNow() {
        val now = System.currentTimeMillis() / 1000
        val elapsed = maxOf(0, now - anchorAt)
        val result = computeStateByElapsed(anchorLights, elapsed)
        val phase = result.phase
        val presentation = phasePresentation(phase)

        for (i in 0 until 5) lights[i] = result.lights[i]
        renderLights(lights, editable = isEditMode)

        tvPhase.text = phaseLabel(phase)
        tvHangarHint.text = presentation.text
        statusBanner.setBackgroundColor(Color.parseColor(presentation.bgColor))
        val durationText = formatDuration(result.remainingSeconds)
        tvCountdown.text = durationText
        renderCountdownDigits(durationText)

        tvHelper.text = if (hasConfirmed) {
            "锚点时间：${formatTimestamp(confirmedAt)}"
        } else {
            "当前为默认基准模式（DEFAULT_BASE_TIME=1735689600），建议先手动确认一次灯状态"
        }
    }

    private fun computeStateByElapsed(anchors: List<String>, elapsed: Long): TimerState {
        var current = anchors.toMutableList()
        var remaining = elapsed

        while (true) {
            val phase = resolvePhase(current)
            val phaseTotal = phaseTotalSeconds(current, phase)

            if (remaining >= phaseTotal) {
                current = advanceWholePhase(current, phase)
                remaining -= phaseTotal
                continue
            }

            if (phase == 'A') {
                val passed = (remaining / RED_TO_GREEN_SECONDS).toInt()
                current = applyTransitions(current, "red", "green", passed)
            } else if (phase == 'B') {
                val passed = (remaining / GREEN_TO_GRAY_SECONDS).toInt()
                current = applyTransitions(current, "green", "gray", passed)
            }

            return TimerState(current.toList(), resolvePhase(current), phaseTotal - remaining)
        }
    }

    private fun advanceWholePhase(lights: MutableList<String>, phase: Char): MutableList<String> {
        return when (phase) {
            'A' -> {
                val redCount = lights.count { it == "red" }
                applyTransitions(lights, "red", "green", redCount)
            }
            'B' -> {
                val greenCount = lights.count { it == "green" }
                applyTransitions(lights, "green", "gray", greenCount)
            }
            else -> DEFAULT_LIGHTS.toMutableList()
        }
    }

    private fun applyTransitions(lights: MutableList<String>, from: String, to: String, steps: Int): MutableList<String> {
        val result = lights.toMutableList()
        var count = 0
        for (i in result.indices) {
            if (count >= steps) break
            if (result[i] == from) {
                result[i] = to
                count++
            }
        }
        return result
    }

    private fun resolvePhase(lights: List<String>): Char = when {
        lights.any { it == "red" } -> 'A'
        lights.any { it == "green" } -> 'B'
        else -> 'C'
    }

    private fun phaseTotalSeconds(lights: List<String>, phase: Char): Long = when (phase) {
        'A' -> lights.count { it == "red" }.toLong() * RED_TO_GREEN_SECONDS
        'B' -> lights.count { it == "green" }.toLong() * GREEN_TO_GRAY_SECONDS
        else -> ALL_GRAY_HOLD_SECONDS
    }

    private fun renderLights(lightList: List<String>, editable: Boolean) {
        lightViews.forEachIndexed { i, frame ->
            val color = lightList[i]
            val ledRes = when (color) {
                "red" -> R.drawable.led_red
                "green" -> R.drawable.led_green
                else -> R.drawable.led_gray
            }
            frame.setBackgroundResource(ledRes)
        }
    }

    private fun shareCalibration() {
        if (isEditMode || !hasConfirmed) {
            Toast.makeText(requireContext(), "请先确认一次灯状态后再分享", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedAt = System.currentTimeMillis() / 1000
        val snapshot = computeStateByElapsed(anchorLights, maxOf(0, sharedAt - anchorAt))
        val phaseTotal = phaseTotalSeconds(snapshot.lights, snapshot.phase)
        val phaseElapsed = maxOf(0, phaseTotal - snapshot.remainingSeconds)

        val payload = JSONObject().apply {
            put("version", 2)
            put("rule", JSONObject().apply {
                put("redToGreen", RED_TO_GREEN_MINUTES)
                put("greenToGray", GREEN_TO_GRAY_MINUTES)
                put("grayHold", ALL_GRAY_HOLD_MINUTES)
            })
            put("confirmedAt", confirmedAt)
            put("lights", org.json.JSONArray(snapshot.lights))
            put("sharedAt", sharedAt)
            put("phaseElapsedSeconds", phaseElapsed)
        }

        val code = "CAL|${Base64.encodeToString(payload.toString().toByteArray(), Base64.NO_WRAP)}"
        etCalibrationInput.setText(code)

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("calibration", code))
        Toast.makeText(requireContext(), "校准码已复制", Toast.LENGTH_SHORT).show()
    }

    private fun applyCalibration() {
        val raw = etCalibrationInput.text.toString().trim()
        if (!raw.startsWith("CAL|")) {
            Toast.makeText(requireContext(), "校准码格式错误", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val decoded = String(Base64.decode(raw.substring(4), Base64.NO_WRAP))
            val parsed = JSONObject(decoded)
            val version = parsed.optInt("version", 1)
            if (version !in 1..2) throw Exception()

            val rule = parsed.optJSONObject("rule") ?: throw Exception()
            if (rule.optInt("redToGreen") != RED_TO_GREEN_MINUTES ||
                rule.optInt("greenToGray") != GREEN_TO_GRAY_MINUTES ||
                rule.optInt("grayHold") != ALL_GRAY_HOLD_MINUTES) {
                throw Exception()
            }

            val calLights = (0 until parsed.getJSONArray("lights").length()).map {
                parsed.getJSONArray("lights").getString(it)
            }
            if (calLights.size != 5 || !calLights.all { it in setOf("red", "green", "gray") }) throw Exception()
            if (!validateLights(calLights)) {
                Toast.makeText(requireContext(), "校准码灯状态组合不合法", Toast.LENGTH_SHORT).show()
                return
            }

            var anchorAt = parsed.optLong("confirmedAt")
            if (version >= 2) {
                val sharedAt = parsed.optLong("sharedAt", 0)
                val phaseElapsed = parsed.optLong("phaseElapsedSeconds", 0)
                if (sharedAt > 0 && phaseElapsed >= 0) {
                    anchorAt = sharedAt - phaseElapsed
                }
            }

            persistAnchor(calLights, anchorAt)
            isEditMode = false
            btnMain.text = "重置灯状态"
            tvTip.text = "自动倒计时与自动变灯进行中"
            setAnchor(calLights, anchorAt, true)

            Toast.makeText(requireContext(), "已按分享锚点校准", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "校准码解析失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncFromExecTimer() {
        if (isEditMode) return
        btnSync.isEnabled = false
        tvSyncInfo.text = "正在同步..."

        Thread {
            try {
                val state = fetchExecTimerState(EXECTIMER_URL)
                val startTime = parseStartTime(state.optString("startTime", ""))
                if (startTime <= 0) throw Exception("startTime 字段无效")

                val at = startTime
                val lights = DEFAULT_LIGHTS.toList()

                handler.post {
                    persistAnchor(lights, at)
                    isEditMode = false
                    btnMain.text = "重置灯状态"
                    tvTip.text = "自动倒计时与自动变灯进行中"
                    tvSyncInfo.text = "已同步：按5红起点 ${formatTimestamp(at)} 对齐"
                    setAnchor(lights, at, true)
                    Toast.makeText(requireContext(), "同步成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.net.UnknownHostException) {
                handler.post {
                    tvSyncInfo.text = "同步失败：无法解析域名 exectimer.com"
                    Toast.makeText(requireContext(), "域名无法解析，请检查网络", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.net.SocketTimeoutException) {
                handler.post {
                    tvSyncInfo.text = "同步失败：连接超时"
                    Toast.makeText(requireContext(), "连接超时，请检查网络", Toast.LENGTH_SHORT).show()
                }
            } catch (e: javax.net.ssl.SSLException) {
                handler.post {
                    tvSyncInfo.text = "同步失败：SSL证书验证失败"
                    Toast.makeText(requireContext(), "SSL错误：${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.net.ConnectException) {
                handler.post {
                    tvSyncInfo.text = "同步失败：无法连接服务器"
                    Toast.makeText(requireContext(), "连接被拒绝：${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    tvSyncInfo.text = "同步失败：${e.message}"
                    Toast.makeText(requireContext(), e.message ?: "同步失败", Toast.LENGTH_SHORT).show()
                }
            } finally {
                handler.post { btnSync.isEnabled = true }
            }
        }.start()
    }

    private fun fetchExecTimerState(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) throw Exception("同步源返回异常(${conn.responseCode})")
            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStartTime(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return -1
        val num = trimmed.toLongOrNull()
        if (num != null) return if (num > 1e12) num / 1000 else num
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.parse(trimmed)?.time?.div(1000) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun validateLights(lights: List<String>): Boolean {
        val hasRed = lights.contains("red")
        val hasGreen = lights.contains("green")
        val hasGray = lights.contains("gray")
        val kinds = listOf(hasRed, hasGreen, hasGray).count { it }
        if (kinds == 1) return true
        if (kinds != 2) return false
        if (hasRed && hasGreen) return true
        if (hasGreen && hasGray) return true
        return false
    }

    private fun nextColor(color: String): String = when (color) {
        "red" -> "green"
        "green" -> "gray"
        else -> "red"
    }

    private fun phaseLabel(phase: Char): String = when (phase) {
        'A' -> "机库关闭"
        'B' -> "机库开启"
        else -> "机库初始化"
    }

    private fun phasePresentation(phase: Char): PhaseInfo = when (phase) {
        'A' -> PhaseInfo("关闭", "#7a2233")
        'B' -> PhaseInfo("开启", "#166534")
        else -> PhaseInfo("初始化中", "#1b3145")
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    private fun renderCountdownDigits(duration: String) {
        val digits = duration.filter { it.isDigit() }
        digitViews.forEachIndexed { index, textView ->
            textView.text = digits.getOrNull(index)?.toString() ?: "0"
        }
    }

    private fun formatTimestamp(ts: Long): String {
        if (ts <= 0) return "-"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ts * 1000))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTimer()
    }

    data class TimerState(val lights: List<String>, val phase: Char, val remainingSeconds: Long)
    data class PhaseInfo(val text: String, val bgColor: String)

    companion object {
        private const val RULE_VERSION = 1
        private const val RED_TO_GREEN_MINUTES = 24
        private const val GREEN_TO_GRAY_MINUTES = 12
        private const val ALL_GRAY_HOLD_MINUTES = 5

        private val RED_TO_GREEN_SECONDS = RED_TO_GREEN_MINUTES * 60L
        private val GREEN_TO_GRAY_SECONDS = GREEN_TO_GRAY_MINUTES * 60L
        private val ALL_GRAY_HOLD_SECONDS = ALL_GRAY_HOLD_MINUTES * 60L

        private val DEFAULT_LIGHTS = listOf("red", "red", "red", "red", "red")

        private const val KEY_LIGHTS = "confirmedLights"
        private const val KEY_CONFIRMED_AT = "confirmedAt"
        private const val KEY_RULE_VERSION = "ruleVersion"

        private const val EXECTIMER_URL = "https://exectimer.com/timer-state.json"
    }
}
