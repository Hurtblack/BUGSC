package com.euedrc.bugsc.blueprint

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
import com.euedrc.bugsc.R
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlueprintFragment : Fragment() {

    // ---- views ----
    private lateinit var tvDataVer: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var containerChips: LinearLayout
    private lateinit var tvLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var containerList: LinearLayout
    private lateinit var btnMore: Button

    // ---- data ----
    private lateinit var repo: BlueprintDataRepository
    private lateinit var translations: CodexTranslations

    /** nameEn → (displayName, categoryTop) pre-built for fast filtering */
    private var allItems: List<BlueprintListItem> = emptyList()
    private var filtered: List<BlueprintListItem> = emptyList()
    private var selectedCategory: String = CAT_ALL
    private var displayCount = PAGE_SIZE

    /** 任务浏览模式数据（首次切到「任务」chip 时懒加载）。 */
    private var allMissions: List<RewardMission> = emptyList()
    private var filteredMissions: List<RewardMission> = emptyList()
    private var missionsLoaded = false
    private val isMissionMode get() = selectedCategory == CAT_MISSIONS

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_blueprint, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDataVer = view.findViewById(R.id.tv_data_ver)
        etSearch = view.findViewById(R.id.et_search)
        btnSearch = view.findViewById(R.id.btn_search)
        containerChips = view.findViewById(R.id.container_category_chips)
        tvLoading = view.findViewById(R.id.tv_loading)
        tvEmpty = view.findViewById(R.id.tv_empty)
        containerList = view.findViewById(R.id.container_list)
        btnMore = view.findViewById(R.id.btn_more)

        repo = BlueprintDataRepository(requireContext())

        btnSearch.setOnClickListener { applyFilter() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { applyFilter(); true } else false
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        btnMore.setOnClickListener {
            displayCount += PAGE_SIZE
            if (isMissionMode) renderMissionList() else renderList()
        }

        loadData()
    }

    // ---- data loading ----

    private fun loadData() {
        tvLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.GONE
        btnMore.visibility = View.GONE

        lifecycleScope.launch {
            val (t, index, ver) = withContext(Dispatchers.IO) {
                val t = repo.loadTranslations()
                val index = repo.loadScCraftIndex()   // reads JSON once for all 1520 entries
                val gameVer = repo.gameVersion(BlueprintDataRepository.Dataset.SCCRAFT) ?: "v${repo.currentVersion(BlueprintDataRepository.Dataset.SCCRAFT)}"
                Triple(t, index, gameVer)
            }

            translations = t
            allItems = index.map { entry ->
                val nameCn = t.itemName(entry.nameEn)
                val catTop = entry.category.split("/").firstOrNull()?.trim() ?: ""
                val materialTokens = entry.materials.flatMap { matEn ->
                    val cn = t.itemName(matEn)
                    if (cn != null && cn != matEn) listOf(matEn.lowercase(), cn.lowercase())
                    else listOf(matEn.lowercase())
                }
                BlueprintListItem(
                    nameEn = entry.nameEn,
                    nameCn = nameCn,
                    displayName = if (nameCn != null && nameCn != entry.nameEn) nameCn else entry.nameEn,
                    categoryTop = catTop,
                    categoryFull = entry.category,
                    craftTimeSeconds = entry.craftTimeSeconds,
                    missionCount = entry.missionCount,
                    materialsEn = entry.materials,
                    materialSearchTokens = materialTokens,
                )
            }.sortedBy { it.displayName }

            tvDataVer.text = "$ver · ${allItems.size}个"
            tvLoading.visibility = View.GONE
            setupCategoryChips()
            applyFilter()
        }
    }

    // ---- category chips ----

    private fun setupCategoryChips() {
        containerChips.removeAllViews()
        listOf(CAT_ALL to "全部", "Armour" to "装甲", "Vehiclegear" to "载具", "Weapons" to "武器", "Ammo" to "弹药", CAT_MISSIONS to "任务").forEach { (key, label) ->
            containerChips.addView(buildCategoryChip(key, label))
        }
    }

    private fun buildCategoryChip(key: String, label: String): Chip {
        return Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = 0f
            val isSelected = key == selectedCategory
            chipBackgroundColor = ColorStateList.valueOf(
                if (isSelected) Color.parseColor("#21d4ff") else Color.parseColor("#14222e")
            )
            setTextColor(if (isSelected) Color.parseColor("#001119") else Color.parseColor("#7c95a8"))
            isChecked = isSelected
            setOnClickListener {
                selectedCategory = key
                setupCategoryChips()
                displayCount = PAGE_SIZE
                applyFilter()
            }
        }
    }

    // ---- filter & render ----

    private fun applyFilter() {
        val query = etSearch.text.toString().trim().lowercase()
        if (isMissionMode) { applyMissionFilter(query); return }
        filtered = allItems.filter { item ->
            val matchCat = selectedCategory == CAT_ALL || item.categoryTop == selectedCategory
            val matchQuery = query.isEmpty()
                || item.displayName.lowercase().contains(query)
                || item.nameEn.lowercase().contains(query)
                || item.materialSearchTokens.any { it.contains(query) }
            matchCat && matchQuery
        }
        displayCount = PAGE_SIZE
        renderList()
    }

    private fun renderList() {
        if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            containerList.visibility = View.GONE
            btnMore.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.VISIBLE

        val toShow = filtered.take(displayCount)
        containerList.removeAllViews()
        toShow.forEach { item ->
            containerList.addView(buildItemCard(item))
        }

        btnMore.visibility = if (filtered.size > displayCount) View.VISIBLE else View.GONE
        btnMore.text = "加载更多（还有 ${filtered.size - displayCount} 个）"
    }

    private fun buildItemCard(item: BlueprintListItem): View {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 8.dp }

        val card = LinearLayout(requireContext()).apply {
            layoutParams = lp
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { openDetail(item.nameEn) }
        }

        // Name row
        val nameRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvName = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = item.displayName
            textSize = 15f
            setTextColor(Color.parseColor("#d8eaf2"))
            typeface = Typeface.DEFAULT_BOLD
        }
        nameRow.addView(tvName)

        if (item.missionCount > 0) {
            val tvMission = TextView(requireContext()).apply {
                text = "任务×${item.missionCount}"
                textSize = 10f
                setTextColor(Color.parseColor("#001119"))
                setBackgroundColor(Color.parseColor("#4ade80"))
                setPadding(4.dp, 2.dp, 4.dp, 2.dp)
            }
            nameRow.addView(tvMission)
        }
        card.addView(nameRow)

        // English name (only when different from display)
        if (item.nameCn != null && item.nameCn != item.nameEn) {
            val tvEn = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 2.dp }
                text = item.nameEn
                textSize = 11f
                setTextColor(Color.parseColor("#4a6377"))
                typeface = Typeface.MONOSPACE
            }
            card.addView(tvEn)
        }

        // Meta row: category + craft time
        val metaRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 6.dp }
        }

        val tvCat = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = item.categoryFull.substringAfter("/").trim().ifBlank { item.categoryFull }
            textSize = 11f
            setTextColor(Color.parseColor("#7c95a8"))
        }
        metaRow.addView(tvCat)

        if (item.craftTimeSeconds > 0) {
            val tvTime = TextView(requireContext()).apply {
                text = formatTime(item.craftTimeSeconds)
                textSize = 11f
                setTextColor(Color.parseColor("#21d4ff"))
                typeface = Typeface.MONOSPACE
            }
            metaRow.addView(tvTime)
        }
        card.addView(metaRow)

        return card
    }

    private fun openDetail(nameEn: String) {
        val sheet = BlueprintDetailSheet.newInstance(nameEn)
        sheet.show(parentFragmentManager, "blueprint_detail")
    }

    // ---- mission browse mode ----

    private fun applyMissionFilter(query: String) {
        if (!missionsLoaded) {
            tvLoading.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            containerList.visibility = View.GONE
            btnMore.visibility = View.GONE
            lifecycleScope.launch {
                val ms = withContext(Dispatchers.IO) { repo.loadAllMissions() }
                allMissions = ms.sortedByDescending { it.rewardUec ?: 0L }
                missionsLoaded = true
                tvLoading.visibility = View.GONE
                if (isMissionMode) filterAndRenderMissions(query)
            }
            return
        }
        filterAndRenderMissions(query)
    }

    private fun filterAndRenderMissions(query: String) {
        filteredMissions = allMissions.filter { m ->
            query.isEmpty()
                || m.displayTitle.lowercase().contains(query)
                || m.title?.lowercase()?.contains(query) == true
                || m.typeLabel?.lowercase()?.contains(query) == true
        }
        displayCount = PAGE_SIZE
        renderMissionList()
    }

    private fun renderMissionList() {
        if (filteredMissions.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            containerList.visibility = View.GONE
            btnMore.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.GONE
        containerList.visibility = View.VISIBLE

        val toShow = filteredMissions.take(displayCount)
        containerList.removeAllViews()
        toShow.forEach { containerList.addView(buildMissionCard(it)) }

        btnMore.visibility = if (filteredMissions.size > displayCount) View.VISIBLE else View.GONE
        btnMore.text = "加载更多（还有 ${filteredMissions.size - displayCount} 个）"
    }

    private fun buildMissionCard(m: RewardMission): View {
        val card = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp }
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                MissionDetailSheet.newInstance(m.guid).show(parentFragmentManager, "mission_detail")
            }
        }
        card.addView(TextView(requireContext()).apply {
            text = m.displayTitle
            textSize = 15f
            setTextColor(Color.parseColor("#d8eaf2"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val metaParts = buildList {
            m.typeLabel?.let { add(it) }
            if (m.isCombat) add("战斗")
            m.rewardUec?.let { add("${java.text.NumberFormat.getInstance(java.util.Locale.US).format(it)} aUEC") }
            if (m.blueprints.isNotEmpty()) add("蓝图×${m.blueprints.size}")
        }
        if (metaParts.isNotEmpty()) {
            card.addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 6.dp }
                text = metaParts.joinToString(" · ")
                textSize = 11f
                setTextColor(Color.parseColor("#21d4ff"))
            })
        }
        return card
    }

    // ---- helpers ----

    private fun formatTime(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val CAT_ALL = "__all__"
        private const val CAT_MISSIONS = "__missions__"
        private const val PAGE_SIZE = 40
    }
}

data class BlueprintListItem(
    val nameEn: String,
    val nameCn: String?,
    val displayName: String,
    val categoryTop: String,
    val categoryFull: String,
    val craftTimeSeconds: Int,
    val missionCount: Int,
    /** 该蓝图所有槽位材料的英文名，用于详情展示。 */
    val materialsEn: List<String> = emptyList(),
    /** 预先小写化的材料英/中文名 token，用于搜索匹配。 */
    val materialSearchTokens: List<String> = emptyList(),
)
