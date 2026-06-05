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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * 任务详情卡。
 * 任务主体来自 SCMDB 任务库；蓝图掉落列表来自蓝图-任务关联表。
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

    /** 默认展开到全高并跳过半展开态，让长内容靠内部滚动而不是把整张卡拖走关闭。 */
    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
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
        m.illegal?.let { if (it) badges.addView(badge("非法", Color.parseColor("#1a0808"), Color.parseColor("#ff6b6b"))) }
        m.canBeShared?.let { if (it) badges.addView(badge("可共享", Color.parseColor("#001119"), Color.parseColor("#8ae234"))) }
        m.onceOnly?.let { if (it) badges.addView(badge("一次性", Color.parseColor("#001119"), Color.parseColor("#ffd166"))) }
        if (badges.childCount > 0) root.addView(badges)

        // 标题
        root.addView(label(m.displayTitle, COLOR_TEXT, 18f).apply {
            typeface = Typeface.DEFAULT_BOLD
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 10.dp
        })
        m.title?.takeIf { it != m.displayTitle }?.let {
            root.addView(label(it, COLOR_SUB, 11f).apply { typeface = Typeface.MONOSPACE })
        }

        root.addView(sectionLabel("任务信息"))
        m.displayFaction?.let { root.addView(kvRow("派系", it, COLOR_ACCENT)) }
        m.availableSystems.takeIf { it.isNotEmpty() }?.let {
            root.addView(kvRow("星系", it.joinToString(" / "), COLOR_TEXT))
        }
        m.pyroRegion.takeIf { it.isNotEmpty() }?.let {
            root.addView(kvRow("派罗区域", it.joinToString(", "), COLOR_TEXT))
        }
        m.maxPlayersPerInstance?.let { root.addView(kvRow("人数上限", it.toString(), COLOR_TEXT)) }

        // 赏金
        m.rewardUec?.let {
            root.addView(kvRow("赏金报酬", "${NumberFormat.getInstance(Locale.US).format(it)} aUEC", Color.parseColor("#ffd166")))
        }
        m.buyIn?.let {
            root.addView(kvRow("接单成本", "${NumberFormat.getInstance(Locale.US).format(it)} aUEC", Color.parseColor("#ff7a7a")))
        }
        // 掉率
        m.rewardChance?.let {
            root.addView(kvRow("蓝图掉率", "${(it * 100).toInt()}%", COLOR_ACCENT))
        }
        m.timeToComplete?.takeIf { it > 0 }?.let {
            root.addView(kvRow("限时", "${it} 分钟", COLOR_TEXT))
        }
        m.personalCooldownTime?.takeIf { m.hasPersonalCooldown == true && it > 0 }?.let {
            root.addView(kvRow("个人冷却", "${it} 分钟", COLOR_TEXT))
        }
        m.abandonedCooldownTime?.takeIf { it > 0 }?.let {
            root.addView(kvRow("放弃冷却", "${it} 分钟", COLOR_TEXT))
        }

        val standing = buildList {
            m.minStanding?.displayName?.let { add("最低 $it${m.minStanding.minReputation?.let { rep -> " ($rep)" } ?: ""}") }
            m.maxStanding?.displayName?.let { add("最高 $it${m.maxStanding.minReputation?.let { rep -> " ($rep)" } ?: ""}") }
        }
        if (standing.isNotEmpty()) root.addView(kvRow("声望要求", standing.joinToString(" → "), COLOR_TEXT))
        if (m.factionRewards.isNotEmpty()) {
            root.addView(sectionLabel("声望奖励"))
            m.factionRewards.forEach { reward ->
                val amount = reward.amount?.let { if (it >= 0) "+$it" else it.toString() } ?: "?"
                val scope = reward.displayScope ?: "声望"
                val faction = reward.displayFaction?.let { " · $it" } ?: ""
                root.addView(label("$amount $scope$faction", COLOR_TEXT, 13f).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4.dp
                })
            }
        }

        // 任务简介
        m.displayDescription?.let {
            root.addView(sectionLabel("任务简介"))
            root.addView(label(it, COLOR_TEXT, 13f).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 6.dp
                setLineSpacing(2.dp.toFloat(), 1.0f)
            })
        }

        // 地点
        if (m.locations.isNotEmpty()) {
            root.addView(sectionLabel("任务地点"))
            addLocations(m.locations)
        }
        if (m.destinations.isNotEmpty()) {
            root.addView(sectionLabel("目的地"))
            addLocations(m.destinations)
        }
        if (m.blueprintRewards.isNotEmpty()) {
            root.addView(sectionLabel("蓝图奖励池"))
            m.blueprintRewards.forEach { reward ->
                val chance = reward.chance?.let { " · ${(it * 100).toInt()}%" } ?: ""
                val trigger = reward.trigger?.let { " · $it" } ?: ""
                root.addView(label("${reward.poolName ?: "Blueprint Pool"}$chance$trigger", COLOR_TEXT, 12f).apply {
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

    private fun addLocations(locations: List<MissionLocation>) {
        val max = 12
        locations.take(max).forEach { loc ->
            val context = loc.context?.let { " · $it" } ?: ""
            root.addView(label("${loc.displayName}$context", COLOR_TEXT, 13f).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4.dp
            })
        }
        if (locations.size > max) {
            root.addView(label("还有 ${locations.size - max} 个地点未展开", COLOR_SUB, 12f).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 4.dp
            })
        }
    }

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
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 10.dp }
        // key：固定宽度靠左
        addView(label(key, COLOR_SUB, 12f).apply {
            layoutParams = LinearLayout.LayoutParams(72.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2.dp
            }
        })
        // value：占满剩余空间、右对齐，长内容自动换行
        addView(label(value, valueColor, 15f).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
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
