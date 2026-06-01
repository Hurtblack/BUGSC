package com.euedrc.bugsc.blueprint

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * 任务详情卡（中档）—— 类型 / 中文标题 / 赏金 / 地点 / 掉率 / 该任务掉落的蓝图列表。
 * 数据来自 flowcld 公开接口（reward-mission-details），见 [RewardMission]。
 * 点击掉落蓝图可跳转该蓝图详情。
 */
class MissionDetailSheet : BottomSheetDialogFragment() {

    private val guid: String get() = requireArguments().getString(ARG_GUID)!!
    private lateinit var root: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_mission_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view.findViewById(R.id.container_root)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val (mission, t) = withContext(Dispatchers.IO) {
                val repo = BlueprintDataRepository(requireContext())
                repo.loadMissionByGuid(guid) to repo.loadTranslations()
            }
            if (mission == null) {
                root.addView(label("任务数据缺失", COLOR_SUB, 14f))
                return@launch
            }
            bind(mission, t)
        }
    }

    private fun bind(m: RewardMission, t: CodexTranslations) {
        root.removeAllViews()

        // 类型徽章行
        val badges = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        m.typeLabel?.let { badges.addView(badge(it, Color.parseColor("#001119"), Color.parseColor("#21d4ff"))) }
        if (m.isCombat) badges.addView(badge("战斗任务", Color.parseColor("#001119"), Color.parseColor("#ff7a7a")))
        if (badges.childCount > 0) root.addView(badges)

        // 标题
        root.addView(label(m.displayTitle, COLOR_TEXT, 18f).apply {
            typeface = Typeface.DEFAULT_BOLD
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 10.dp
        })
        m.title?.takeIf { it != m.displayTitle }?.let {
            root.addView(label(it, COLOR_SUB, 11f).apply { typeface = Typeface.MONOSPACE })
        }

        // 赏金
        m.rewardUec?.let {
            root.addView(kvRow("赏金报酬", "${NumberFormat.getInstance(Locale.US).format(it)} aUEC", Color.parseColor("#ffd166")))
        }
        // 掉率
        m.rewardChance?.let {
            root.addView(kvRow("蓝图掉率", "${(it * 100).toInt()}%", COLOR_ACCENT))
        }

        // 地点
        if (m.locations.isNotEmpty()) {
            root.addView(sectionLabel("任务地点"))
            m.locations.forEach { loc ->
                val sys = loc.displaySystem?.let { " · $it" } ?: ""
                root.addView(label("${loc.displayName}$sys", COLOR_TEXT, 13f).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4.dp
                })
            }
        }

        // 可获得蓝图（反向掉落列表）
        if (m.blueprints.isNotEmpty()) {
            root.addView(sectionLabel("可获得蓝图（${m.blueprints.size}）"))
            m.blueprints.forEach { bp ->
                val cn = t.itemName(bp.nameEn)
                val display = if (cn != null && cn != bp.nameEn) cn else bp.nameEn
                val chance = bp.dropChance?.let { " · ${(it * 100).toInt()}%" } ?: ""
                val rowView = label("• $display$chance", COLOR_TEXT, 13f).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4.dp
                    isClickable = true
                    setOnClickListener {
                        BlueprintDetailSheet.newInstance(bp.nameEn)
                            .show(parentFragmentManager, "blueprint_detail")
                    }
                }
                root.addView(rowView)
            }
        }
    }

    // ---- view builders ----

    private fun badge(text: String, fg: Int, bg: Int) = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setTextColor(fg)
        setBackgroundColor(bg)
        setPadding(8.dp, 3.dp, 8.dp, 3.dp)
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )).also { it.rightMargin = 6.dp; layoutParams = it }
    }

    private fun kvRow(key: String, value: String, valueColor: Int) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 10.dp }
        addView(label(key, COLOR_SUB, 12f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(label(value, valueColor, 15f).apply { typeface = Typeface.DEFAULT_BOLD })
    }

    private fun sectionLabel(text: String) = label(text, COLOR_ACCENT, 13f).apply {
        typeface = Typeface.DEFAULT_BOLD
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 16.dp
    }

    private fun label(text: String, color: Int, size: Float) = TextView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private val Int.dp: Int get() = (this * (resources?.displayMetrics?.density ?: 1f)).toInt()

    companion object {
        private const val ARG_GUID = "guid"
        private val COLOR_TEXT = Color.parseColor("#d8eaf2")
        private val COLOR_SUB = Color.parseColor("#7c95a8")
        private val COLOR_ACCENT = Color.parseColor("#21d4ff")

        fun newInstance(guid: String) = MissionDetailSheet().apply {
            arguments = Bundle().apply { putString(ARG_GUID, guid) }
        }
    }
}
