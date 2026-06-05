package com.euedrc.bugsc

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.euedrc.bugsc.blueprint.BlueprintDataRepository
import com.euedrc.bugsc.blueprint.MissionDetailSheet
import com.euedrc.bugsc.blueprint.RewardMission
import com.euedrc.bugsc.blueprint.matchesMissionQuery
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MissionQueryFragment : Fragment() {

    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var systemChips: LinearLayout
    private lateinit var factionChips: LinearLayout
    private lateinit var tvLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var containerList: LinearLayout

    private lateinit var repo: BlueprintDataRepository

    private var allMissions: List<RewardMission> = emptyList()
    private var filteredMissions: List<RewardMission> = emptyList()
    private var selectedSystem: String = FILTER_ALL
    private var selectedFaction: String = FILTER_ALL
    private var renderJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_mission_query, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = BlueprintDataRepository(requireContext())

        tvCount = view.findViewById(R.id.tv_count)
        etSearch = view.findViewById(R.id.et_search)
        btnSearch = view.findViewById(R.id.btn_search)
        systemChips = view.findViewById(R.id.container_system_chips)
        factionChips = view.findViewById(R.id.container_faction_chips)
        tvLoading = view.findViewById(R.id.tv_loading)
        tvEmpty = view.findViewById(R.id.tv_empty)
        containerList = view.findViewById(R.id.container_list)

        btnSearch.setOnClickListener { applyFilters() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyFilters()
                true
            } else {
                false
            }
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilters()
        })

        loadMissions()
    }

    private fun loadMissions() {
        tvLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.GONE
        lifecycleScope.launch {
            val missions = withContext(Dispatchers.IO) {
                repo.loadAllMissions().sortedByDescending { it.rewardUec ?: 0L }
            }
            allMissions = missions
            tvCount.text = "${missions.size}个"
            setupFilterChips()
            tvLoading.visibility = View.GONE
            applyFilters()
        }
    }

    private fun setupFilterChips() {
        val systems = listOf(FILTER_ALL) + allMissions
            .flatMap { it.availableSystems }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val factions = listOf(FILTER_ALL) + allMissions
            .mapNotNull { it.displayFaction?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()

        systemChips.removeAllViews()
        systems.forEach { value ->
            systemChips.addView(buildFilterChip(value, selectedSystem == value) {
                selectedSystem = value
                setupFilterChips()
                applyFilters()
            })
        }

        factionChips.removeAllViews()
        factions.forEach { value ->
            factionChips.addView(buildFilterChip(value, selectedFaction == value) {
                selectedFaction = value
                setupFilterChips()
                applyFilters()
            })
        }
    }

    private fun buildFilterChip(label: String, selected: Boolean, onClick: () -> Unit): Chip {
        val text = if (label == FILTER_ALL) "全部" else label
        return Chip(requireContext()).apply {
            this.text = text
            isCheckable = true
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = 0f
            chipBackgroundColor = ColorStateList.valueOf(
                if (selected) Color.parseColor("#21d4ff") else Color.parseColor("#14222e")
            )
            setTextColor(if (selected) Color.parseColor("#001119") else Color.parseColor("#7c95a8"))
            isChecked = selected
            setOnClickListener { onClick() }
        }
    }

    private fun applyFilters() {
        if (allMissions.isEmpty()) return
        val query = etSearch.text.toString().trim()
        filteredMissions = allMissions.filter { mission ->
            mission.matchesMissionQuery(
                query = query,
                selectedSystem = selectedSystem,
                selectedFaction = selectedFaction,
            )
        }
        renderList()
    }

    private fun renderList() {
        // 取消上一轮未完成的渲染，避免搜索连打时卡片堆叠
        renderJob?.cancel()
        containerList.removeAllViews()
        if (filteredMissions.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            containerList.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.VISIBLE

        // 分批渲染：首屏立即出现，其余逐帧补齐，避免一次性构建上千卡片卡住主线程
        val list = filteredMissions
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            list.forEachIndexed { index, mission ->
                containerList.addView(buildMissionCard(mission))
                if ((index + 1) % RENDER_BATCH == 0) delay(BATCH_DELAY_MS)
            }
        }
    }

    private fun buildMissionCard(mission: RewardMission): View {
        val card = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                MissionDetailSheet.newInstance(mission.guid).show(parentFragmentManager, "mission_detail")
            }
        }
        card.addView(cardText(mission.displayTitle, "#d8eaf2", 15f, bold = true))
        mission.cardSecondaryLine.takeIf { it.isNotBlank() }?.let {
            card.addView(cardText(it, "#7c95a8", 11f, topMarginDp = 6))
        }
        mission.cardTertiaryBadges.takeIf { it.isNotEmpty() }?.let {
            card.addView(cardText(it.joinToString(" · "), "#21d4ff", 11f, topMarginDp = 6))
        }
        return card
    }

    private fun cardText(text: String, color: String, sizeSp: Float, bold: Boolean = false, topMarginDp: Int = 0) =
        TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = topMarginDp.dp }
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val FILTER_ALL = "__all__"
        private const val RENDER_BATCH = 20
        private const val BATCH_DELAY_MS = 8L
    }
}
