package com.euedrc.bugsc.blueprint

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlueprintDetailSheet : BottomSheetDialogFragment() {

    private val nameEn: String get() = requireArguments().getString(ARG_NAME_EN)!!

    // ---- views ----
    private lateinit var tvNameCn: TextView
    private lateinit var tvNameEn: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvCraftTime: TextView
    private lateinit var containerSlots: LinearLayout
    private lateinit var containerStats: LinearLayout
    private lateinit var tvStatsHint: TextView
    private lateinit var tvMissionsLabel: TextView
    private lateinit var containerMissions: LinearLayout

    // ---- state ----
    private var blueprint: ScCraftBlueprint? = null
    private var translations: CodexTranslations = CodexTranslations.EMPTY
    private var missionTranslations: Map<String, String> = emptyMap()
    private var slotTranslations: Map<String, String> = emptyMap()
    private var missions: List<RewardMission> = emptyList()
    /** statLocKey → 绝对基准值（来自 star-citizen.wiki）；无基准的属性不在此表。 */
    private var baseStats: Map<String, Double> = emptyMap()
    /** slot index → current quality */
    private val slotQuality = mutableMapOf<Int, Int>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_blueprint_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvNameCn = view.findViewById(R.id.tv_name_cn)
        tvNameEn = view.findViewById(R.id.tv_name_en)
        tvCategory = view.findViewById(R.id.tv_category)
        tvCraftTime = view.findViewById(R.id.tv_craft_time)
        containerSlots = view.findViewById(R.id.container_slots)
        containerStats = view.findViewById(R.id.container_stats)
        tvStatsHint = view.findViewById(R.id.tv_stats_hint)
        tvMissionsLabel = view.findViewById(R.id.tv_missions_label)
        containerMissions = view.findViewById(R.id.container_missions)

        loadBlueprint()
    }

    private fun loadBlueprint() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val repo = BlueprintDataRepository(requireContext())
                LoadedData(
                    blueprint = repo.loadScCraftBlueprint(nameEn),
                    translations = repo.loadTranslations(),
                    missionTranslations = repo.loadMissionTranslations(),
                    slotTranslations = repo.loadSlotTranslations(),
                    baseStats = repo.loadItemBaseStatsFor(nameEn),
                    missions = repo.loadMissionsForBlueprint(nameEn),
                )
            }
            translations = loaded.translations
            missionTranslations = loaded.missionTranslations
            slotTranslations = loaded.slotTranslations
            baseStats = loaded.baseStats
            missions = loaded.missions
            blueprint = loaded.blueprint ?: return@launch
            val bp = loaded.blueprint
            bindHeader(bp, translations)
            bindSlots(bp, translations)
            bindStats(bp)
            bindMissions()
        }
    }

    private fun bindHeader(bp: ScCraftBlueprint, t: CodexTranslations) {
        val nameCn = t.itemName(bp.nameEn) ?: fallbackItemNameCn(bp.nameEn)
        tvNameCn.text = nameCn ?: bp.nameEn
        tvNameEn.text = bp.nameEn
        tvNameEn.visibility = if (nameCn != null && nameCn != bp.nameEn) View.VISIBLE else View.GONE

        tvCategory.text = bp.category.split("/").lastOrNull()?.trim() ?: bp.category
        tvCraftTime.text = if (bp.craftTimeSeconds > 0) "制作 ${formatTime(bp.craftTimeSeconds)}" else ""
    }

    private fun bindSlots(bp: ScCraftBlueprint, t: CodexTranslations) {
        containerSlots.removeAllViews()
        if (bp.slots.isEmpty()) {
            addLabel(containerSlots, "暂无材料数据", Color.parseColor("#4a6377"))
            return
        }

        bp.slots.forEachIndexed { i, slot ->
            slotQuality[i] = MAX_QUALITY / 2
            val hasQualityEffects = slot.qualityEffects.isNotEmpty()

            if (i > 0) addDivider(containerSlots)

            // 槽位名 + 材料名（带翻译）
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tvSlotName = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = localizeSlot(slot.slot, slotTranslations)
                textSize = 13f
                setTextColor(Color.parseColor("#d8eaf2"))
                typeface = Typeface.DEFAULT_BOLD
            }
            headerRow.addView(tvSlotName)

            // 材料中文名（截掉括号后缀），英文名作为 fallback
            val matCn = (t.itemName(slot.material) ?: fallbackItemNameCn(slot.material))
                ?.substringBefore("(")
                ?.trim()
            val tvMaterial = TextView(requireContext()).apply {
                text = matCn ?: slot.material
                textSize = 12f
                setTextColor(Color.parseColor("#21d4ff"))
                typeface = if (matCn == null) Typeface.MONOSPACE else Typeface.DEFAULT
            }
            headerRow.addView(tvMaterial)
            containerSlots.addView(headerRow)

            // 材料用量
            val tvAmount = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 2.dp }
                text = "${slot.quantityScu} SCU · 品质 0~$MAX_QUALITY"
                textSize = 11f
                setTextColor(Color.parseColor("#4a6377"))
            }
            containerSlots.addView(tvAmount)

            // 品质滑块
            val sliderRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 4.dp }
            }

            sliderRow.addView(label("0", Color.parseColor("#4a6377"), 10f))

            val seekBar = SeekBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 4.dp; marginEnd = 4.dp }
                max = MAX_QUALITY
                progress = MAX_QUALITY / 2
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#21d4ff"))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#21d4ff"))
                isEnabled = hasQualityEffects
                alpha = if (hasQualityEffects) 1f else 0.5f
            }
            sliderRow.addView(seekBar)

            sliderRow.addView(label(MAX_QUALITY.toString(), Color.parseColor("#4a6377"), 10f))

            val tvQualityVal = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(36.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = 6.dp }
                text = if (hasQualityEffects) (MAX_QUALITY / 2).toString() else "--"
                textSize = 12f
                setTextColor(if (hasQualityEffects) Color.parseColor("#21d4ff") else Color.parseColor("#4a6377"))
                typeface = Typeface.MONOSPACE
                gravity = android.view.Gravity.END
            }
            sliderRow.addView(tvQualityVal)

            if (hasQualityEffects) {
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        tvQualityVal.text = progress.toString()
                        slotQuality[i] = progress
                        blueprint?.let { refreshStats(it) }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            containerSlots.addView(sliderRow)

            if (!hasQualityEffects) {
                containerSlots.addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = 2.dp }
                    text = "说明：该槽位在当前配方数据中不提供品质修正，不影响下方属性结果"
                    textSize = 10f
                    setTextColor(Color.parseColor("#7c95a8"))
                })
            }
        }
    }

    private fun bindStats(bp: ScCraftBlueprint) {
        if (bp.category.contains("Armour", ignoreCase = true)) {
            tvStatsHint.text = "护甲基础减伤通常为轻甲20% / 中甲30% / 重甲40%，颜色款式一般不改变该基线"
        }
        refreshStats(bp)
    }

    private fun refreshStats(bp: ScCraftBlueprint) {
        val qualityBySlot = bp.slots.mapIndexed { i, slot ->
            slot.slot to (slotQuality[i] ?: 0)
        }.toMap()

        val deltas = bp.aggregateDeltas(qualityBySlot)

        containerStats.removeAllViews()

        if (deltas.isEmpty()) {
            addLabel(containerStats, "无品质影响数据", Color.parseColor("#4a6377"))
            return
        }

        // 说明文字：负向修正对后坐力/温度等属性可能是有利的
        val hasInverted = bp.slots.any { slot ->
            slot.qualityEffects.any { it.modifierAtMin > it.modifierAtMax }
        }
        if (hasInverted) {
            val tvNote = TextView(requireContext()).apply {
                text = "⚠ 部分属性越小越好（后坐力/温度/燃料消耗等），负值修正为有利效果"
                textSize = 11f
                setTextColor(Color.parseColor("#FF8A3D"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 6.dp }
            }
            containerStats.addView(tvNote)
        }

        // statLocKey 查表：用于从基准值表取绝对值（aggregateDeltas 按显示名聚合）
        val locKeyByStat = bp.slots.flatMap { it.qualityEffects }.associate { it.stat to it.statLocKey }
        val deltaByLocKey = mutableMapOf<String, Double>()
        deltas.forEach { (stat, delta) ->
            val key = locKeyByStat[stat].orEmpty()
            if (key.isNotBlank()) {
                deltaByLocKey[key] = (deltaByLocKey[key] ?: 0.0) + delta
            }
        }

        // 表头：属性 | 基础 | 强化 | 变化
        containerStats.addView(statRow("属性", "基础", "强化", "变化", isHeader = true))

        // SCM 风格：武器显示派生 DPS 行（单发伤害 * RPM / 60）
        val baseDamage = baseStats["statname_gpp_weapon_damage"]
        val baseRpm = baseStats["statname_gpp_weapon_firerate"]
        if (baseDamage != null && baseRpm != null) {
            val dmgDelta = deltaByLocKey["statname_gpp_weapon_damage"] ?: 0.0
            val rpmDelta = deltaByLocKey["statname_gpp_weapon_firerate"] ?: 0.0
            val enhancedDamage = baseDamage * (1.0 + dmgDelta)
            val enhancedRpm = baseRpm * (1.0 + rpmDelta)
            val baseDps = baseDamage * baseRpm / 60.0
            val enhancedDps = enhancedDamage * enhancedRpm / 60.0
            val dpsChange = if (baseDps == 0.0) 0.0 else (enhancedDps / baseDps - 1.0)
            containerStats.addView(
                statRow(
                    "武器 DPS",
                    "%.2f".format(baseDps),
                    "%.2f".format(enhancedDps),
                    formatChangeText(dpsChange, false),
                )
            )
        }

        var missingBaseCount = 0
        deltas.entries.sortedBy { it.key }.forEach { (stat, delta) ->
            val statLocKey = locKeyByStat[stat].orEmpty()
            if (statLocKey == "statname_gpp_armor_damagemitigation") {
                val armorRows = listOf(
                    "物理减伤" to "statname_gpp_armor_damagemitigation",
                    "能量减伤" to "statname_gpp_armor_damagemitigation_energy",
                    "畸变减伤" to "statname_gpp_armor_damagemitigation_distortion",
                    "热减伤" to "statname_gpp_armor_damagemitigation_thermal",
                    "生化减伤" to "statname_gpp_armor_damagemitigation_biochemical",
                    "眩晕减伤" to "statname_gpp_armor_damagemitigation_stun",
                )
                var added = 0
                armorRows.forEach { (label, key) ->
                    val baseMitigation = baseStats[key] ?: return@forEach
                    val enhancedMitigation = baseMitigation * (1.0 + delta)
                    containerStats.addView(
                        statRow(
                            label,
                            signedPercent(baseMitigation),
                            signedPercent(enhancedMitigation),
                            formatChangeText(delta, false),
                        )
                    )
                    added += 1
                }
                if (added == 0) {
                    missingBaseCount += 1
                    containerStats.addView(statRow("护甲减伤", "暂无基准", "暂无基准", formatChangeText(delta, false)))
                }
                return@forEach
            }
            val base = baseStats[statLocKey]
            val isAbsoluteStep = statLocKey == "statname_gpp_itemresource_powergeneration"
            val changeText = formatChangeText(delta, isAbsoluteStep)
            if (base != null) {
                val enhanced = if (isAbsoluteStep) base + delta else base * (1.0 + delta)
                containerStats.addView(
                    statRow(localizeStatName(stat, statLocKey), formatStatValue(base), formatStatValue(enhanced), changeText)
                )
            } else {
                // 无绝对基准：后坐力类按 SCM 风格展示 0% -> 变化%；其他保留暂无基准提示
                val isRecoil = statLocKey == "statname_gpp_weapon_recoil_smoothness" ||
                    statLocKey == "statname_gpp_weapon_recoil_handling" ||
                    statLocKey == "statname_gpp_weapon_recoil_kick"
                if (isRecoil) {
                    val enhancedPct = signedPercent(delta)
                    containerStats.addView(statRow(localizeStatName(stat, statLocKey), "+0.00%", enhancedPct, "."))
                } else {
                    missingBaseCount += 1
                    containerStats.addView(statRow(localizeStatName(stat, statLocKey), "暂无基准", "暂无基准", changeText))
                }
            }
        }

        if (missingBaseCount > 0) {
            containerStats.addView(TextView(requireContext()).apply {
                text = "注：$missingBaseCount 项属性暂无基准值（来源数据缺失），不影响变化趋势显示"
                textSize = 11f
                setTextColor(Color.parseColor("#FF8A3D"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8.dp }
            })
        }

    }

    /** 一行四列：属性名 | 基础 | 强化 | 变化。isHeader 用更暗的标题样式。 */
    private fun statRow(name: String, base: String, enhanced: String, change: String, isHeader: Boolean = false): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (isHeader) 0 else 4.dp }
        }
        val nameColor = if (isHeader) Color.parseColor("#4a6377") else Color.parseColor("#7c95a8")
        val valColor = if (isHeader) Color.parseColor("#4a6377") else Color.parseColor("#d8eaf2")
        fun cell(text: String, weight: Float, color: Int, mono: Boolean): TextView =
            TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                this.text = text
                textSize = if (isHeader) 11f else 13f
                setTextColor(color)
                if (mono) typeface = Typeface.MONOSPACE
                if (weight > 1.5f) gravity = android.view.Gravity.START else gravity = android.view.Gravity.END
            }
        row.addView(cell(name, 2.2f, nameColor, false))
        row.addView(cell(base, 1.4f, valColor, true))
        row.addView(cell(enhanced, 1.4f, valColor, true))
        // 变化列：中性青色（正/负本身不代表好坏，后坐力负值=有利）
        val changeColor = if (isHeader) Color.parseColor("#4a6377")
        else if (change == "+0.0%" || change == "0.0%") Color.parseColor("#4a6377")
        else Color.parseColor("#21d4ff")
        row.addView(cell(change, 1.2f, changeColor, true))
        return row
    }

    /** 基准值格式化：大数千分位无小数，小数按量级保留有效位。 */
    private fun formatStatValue(v: Double): String = when {
        v == 0.0 -> "0"
        Math.abs(v) >= 1000 -> "%,.0f".format(v)
        Math.abs(v) >= 10 -> "%.1f".format(v)
        Math.abs(v) >= 1 -> "%.2f".format(v)
        else -> "%.4f".format(v)
    }

    private fun formatChangeText(delta: Double, isAbsoluteStep: Boolean): String {
        if (kotlin.math.abs(delta) < 1e-9) return "."
        return if (isAbsoluteStep) {
            val rounded = delta.toInt()
            if (rounded >= 0) "+$rounded" else rounded.toString()
        } else {
            signedPercent(delta)
        }
    }

    private fun signedPercent(delta: Double): String {
        val pct = delta * 100.0
        val sign = if (pct >= 0) "+" else ""
        return "$sign${"%.2f".format(pct)}%"
    }

    private fun bindMissions() {
        if (missions.isEmpty()) {
            tvMissionsLabel.visibility = View.GONE
            containerMissions.visibility = View.GONE
            return
        }
        tvMissionsLabel.visibility = View.VISIBLE
        containerMissions.visibility = View.VISIBLE
        containerMissions.removeAllViews()

        missions.forEachIndexed { i, mission ->
            if (i > 0) addDivider(containerMissions)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    MissionDetailSheet.newInstance(mission.guid)
                        .show(parentFragmentManager, "mission_detail")
                }
            }
            // 标题行：中文标题（已洗占位）+ 类型/赏金小标签
            row.addView(TextView(requireContext()).apply {
                text = mission.displayTitle
                textSize = 13f
                setTextColor(Color.parseColor("#d8eaf2"))
            })
            val metaParts = buildList {
                mission.typeLabel?.let { add(it) }
                if (mission.isCombat) add("战斗")
                mission.rewardUec?.let { add("${java.text.NumberFormat.getInstance(java.util.Locale.US).format(it)} aUEC") }
            }
            if (metaParts.isNotEmpty()) {
                row.addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = 2.dp }
                    text = metaParts.joinToString(" · ")
                    textSize = 11f
                    setTextColor(Color.parseColor("#21d4ff"))
                })
            }
            containerMissions.addView(row)
        }
    }

    // ---- util ----

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1,
            ).apply { topMargin = 8.dp; bottomMargin = 8.dp }
            setBackgroundColor(Color.parseColor("#1B3145"))
        })
    }

    private fun addLabel(parent: LinearLayout, text: String, color: Int) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
        })
    }

    private fun label(text: String, color: Int, size: Float) = TextView(requireContext()).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun formatTime(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private val Int.dp: Int
        get() = (this * (resources?.displayMetrics?.density ?: 1f)).toInt()

    private data class LoadedData(
        val blueprint: ScCraftBlueprint?,
        val translations: CodexTranslations,
        val missionTranslations: Map<String, String>,
        val slotTranslations: Map<String, String>,
        val baseStats: Map<String, Double>,
        val missions: List<RewardMission>,
    )

    companion object {
        private const val ARG_NAME_EN = "name_en"
        private const val MAX_QUALITY = 1000

        fun newInstance(nameEn: String) = BlueprintDetailSheet().apply {
            arguments = Bundle().apply { putString(ARG_NAME_EN, nameEn) }
        }

        private val FALLBACK_ITEM_CN = mapOf(
            "Armoured Carapace" to "装甲甲壳",
            "Armored Carapace" to "装甲甲壳"
        )

        private fun fallbackItemNameCn(nameEn: String): String? {
            return FALLBACK_ITEM_CN[nameEn.trim()]
        }

        fun localizeSlot(slot: String, slotTranslations: Map<String, String> = emptyMap()): String {
            val key = slot.trim().uppercase()
            slotTranslations[key]?.let { return it }
            return when (key) {
            "FRAME" -> "机匣"
            "REINFORCED FRAME" -> "强化框架"
            "CYCLER" -> "循环器"
            "BARREL" -> "枪管"
            "BARRELS" -> "枪管组"
            "OPTICS" -> "瞄准镜"
            "STOCK" -> "枪托"
            "POWERPLANT", "POWER_PLANT" -> "动力系统"
            "COOLER" -> "冷却器"
            "COOLANT" -> "冷却液"
            "SHIELD" -> "护盾"
            "QUANTUM" -> "量子驱动"
            "ARMOR" -> "装甲板"
            "ARMOURED CARAPACE", "ARMORED CARAPACE" -> "装甲甲壳"
            "SHELL" -> "外壳"
            "CASE" -> "机箱"
            "CASING" -> "壳体"
            "CASING WEAVE" -> "壳体织层"
            "PADDING" -> "内衬"
            "INSULATIVE LINER", "LNSULATIVE LINER" -> "隔热内衬"
            "SUIT UNDERLAY" -> "套装底层"
            "PLATING" -> "装甲镀板"
            "PLATING BARRIER" -> "镀板隔层"
            "SEGMENT PANELING", "SEGMENT PANELLING" -> "分段面板"
            "PANEL COVERING" -> "面板覆层"
            "PROTECTIVE SHEATHING" -> "防护护套"
            "SHEATHING COATING" -> "护套涂层"
            "SUPPORT STRUCTURE" -> "支撑结构"
            "VISOR" -> "面镜"
            "UNDERSUIT" -> "内套"
            "MAGAZINE", "MAG" -> "弹匣"
            "SHOT" -> "霰弹"
            "CHARGE CHAMBER" -> "充能室"
            "STABILIZER" -> "稳定器"
            "TORQUE JUNCTION" -> "扭矩接点"
            "DRIVE MOTOR" -> "驱动电机"
            "ACCELERATION UNIT" -> "加速单元"
            "DISTORTER UNIT" -> "畸变单元"
            "RADIATOR" -> "散热器"
            "THERMAL SINK" -> "散热沉板"
            "TENSIVE FIBRE" -> "张力纤维"
            "ANCHOR PINS" -> "锚定销"
            "BUS BARS" -> "汇流排"
            "CABLING" -> "线缆"
            "WIRING" -> "导线"
            "CONDUIT" -> "导管"
            "CONDUIT CHANNEL" -> "导管通道"
            "INTERNAL ARRAY" -> "内部阵列"
            "FIELD ARRAY" -> "力场阵列"
            "CONTAINMENT MATRIX" -> "约束矩阵"
            "STATOR CORES" -> "定子核心"
            "VOLTAGE REGULATOR" -> "电压调节器"
            "FREQUENCY CONTROLLER" -> "频率控制器"
            "SIGNAL PROCESSOR" -> "信号处理器"
            "TRANSMITTER" -> "发射器"
            "INJECTOR NOZZLES" -> "注入喷嘴"
            "PUMP IMPELLER" -> "泵叶轮"
            "REGULATOR" -> "调节器"
            "MAIN VALVE" -> "主阀"
            "GRIP" -> "握把"
            "LENS" -> "镜片"
            "LENSES" -> "镜片组"
            "APERTURE IRIS", "APETURE IRIS" -> "光圈叶片"
            "EMITTER" -> "发射单元"
            "FOCUS ZONE PLATE" -> "聚焦区板"
            "PRECISION PARTS" -> "精密部件"
            "OCULAR ENHANCER" -> "目镜增强器"
            "FILTER" -> "滤片"
            "CORE" -> "核心"
            "ELECTRONICS" -> "电子组件"
            "MAGNETIZER" -> "磁化器"
            "LIMB" -> "弓臂"
            else -> slot.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
        }
        }

        fun localizeStatName(stat: String, statLocKey: String = ""): String = when {
            statLocKey == "statname_gpp_itemresource_powergeneration" -> "电力点数 (Power Pips)"
            statLocKey == "statname_gpp_weapon_recoil_smoothness" -> "后坐平滑"
            statLocKey == "statname_gpp_weapon_recoil_handling" -> "后坐操控"
            statLocKey == "statname_gpp_weapon_recoil_kick" -> "后坐冲击"
            statLocKey == "ui_weapons_tractor_beamforce" -> "牵引束力度"
            statLocKey == "statname_gpp_itemresource_coolantgeneration" -> "冷却效率"
            statLocKey == "statname_gpp_armor_damagemitigation" -> "伤害减免"
            statLocKey == "statname_gpp_hullscraping_efficiency" -> "刮削效率"
            statLocKey == "statname_gpp_weapon_firerate" -> "射速"
            statLocKey == "statname_gpp_tractor_fullstrengthdistance" -> "全效距离"
            statLocKey == "statname_gpp_weapon_damage" -> "武器伤害"
            statLocKey == "statname_gpp_health_maxhealth" -> "耐久度"
            statLocKey == "statname_gpp_armor_temperaturemax" -> "最高温度"
            statLocKey == "statname_gpp_armor_temperaturemin" -> "最低温度"
            statLocKey == "statname_gpp_radar_maxaimassistdistance" -> "最大辅助距离"
            statLocKey == "statname_gpp_tractor_maxdistance" -> "最大距离"
            statLocKey == "statname_gpp_shield_maxhealth" -> "最大护盾强度"
            statLocKey == "statname_gpp_tractor_maxvolume" -> "最大容积"
            statLocKey == "statname_gpp_radar_minaimassistdistance" -> "最小辅助距离"
            statLocKey == "statname_gpp_quantum_fuelrequirement" -> "量子燃料消耗"
            statLocKey == "statname_gpp_quantum_speed" -> "量子速度"
            statLocKey == "statname_gpp_armor_radiationdissipation" -> "辐射散逸"
            statLocKey == "statname_gpp_armor_radiationcapacity" -> "辐射容量"
            statLocKey == "statname_gpp_armor_gforceresistance" -> "过载抗性"
            statLocKey == "statname_gpp_armor_signature_em" -> "电磁特征"
            statLocKey == "statname_gpp_armor_damagemitigation_energy" -> "能量减伤"
            statLocKey == "statname_gpp_armor_damagemitigation_distortion" -> "畸变减伤"
            statLocKey == "statname_gpp_armor_damagemitigation_thermal" -> "热减伤"
            statLocKey == "statname_gpp_armor_damagemitigation_biochemical" -> "生化减伤"
            statLocKey == "statname_gpp_armor_damagemitigation_stun" -> "眩晕减伤"
            statLocKey == "statname_gpp_hullscraping_radius" -> "刮削半径"
            statLocKey == "statname_gpp_hullscraping_speed" -> "刮削速度"
            else -> when (stat) {
            "Integrity" -> "耐久度"
            "Impact Force" -> "冲击力"
            "Damage Mitigation" -> "伤害减免"
            "Recoil Smoothness" -> "后坐力平滑"
            "Recoil Handling" -> "后坐力操控"
            "Recoil Kick" -> "后坐力冲击"
            "Power Pips" -> "电力点数 (Power Pips)"
            "Fire Rate" -> "射速"
            "Max Temp" -> "最高温度"
            "Min Temp" -> "最低温度"
            "Coolant Rating" -> "冷却效率"
            "Max. Shield Strength" -> "最大护盾强度"
            "Quantum Speed" -> "量子速度"
            "Quantum Fuel Burn" -> "量子燃料消耗"
            "Min. Assist Distance" -> "最小辅助距离"
            "Max. Assist Distance" -> "最大辅助距离"
            "Full Strength Dist." -> "全效距离"
            "Max. Distance" -> "最大距离"
            "Beam Force" -> "射束力"
            "Max. Volume" -> "最大容积"
            "Radius" -> "半径"
            else -> stat
        }
        }
    }
}
