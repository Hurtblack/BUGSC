package com.euedrc.bugsc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.data.Bug
import com.euedrc.bugsc.data.BugRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BugListFragment : Fragment() {

    private lateinit var repository: BugRepository
    private lateinit var etSearch: EditText
    private lateinit var etShareCode: EditText
    private lateinit var spinnerGpu: Spinner
    private lateinit var spinnerCpu: Spinner
    private lateinit var spinnerVram: Spinner
    private lateinit var spinnerRam: Spinner
    private lateinit var spinnerSort: Spinner
    private lateinit var switchFav: Switch
    private lateinit var containerTags: LinearLayout
    private lateinit var containerBugs: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var btnSearch: Button
    private lateinit var btnClear: Button
    private lateinit var btnSubmit: Button
    private lateinit var btnImport: Button

    private val selectedTypeTags = mutableListOf<String>()
    private var allBugs: List<Bug> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_bug_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = BugRepository(requireContext())

        etSearch = view.findViewById(R.id.et_search)
        etShareCode = view.findViewById(R.id.et_share_code)
        spinnerGpu = view.findViewById(R.id.spinner_gpu)
        spinnerCpu = view.findViewById(R.id.spinner_cpu)
        spinnerVram = view.findViewById(R.id.spinner_vram)
        spinnerRam = view.findViewById(R.id.spinner_ram)
        spinnerSort = view.findViewById(R.id.spinner_sort)
        switchFav = view.findViewById(R.id.switch_fav)
        containerTags = view.findViewById(R.id.container_tags)
        containerBugs = view.findViewById(R.id.container_bugs)
        tvEmpty = view.findViewById(R.id.tv_empty)
        btnSearch = view.findViewById(R.id.btn_search)
        btnClear = view.findViewById(R.id.btn_clear)
        btnSubmit = view.findViewById(R.id.btn_submit)
        btnImport = view.findViewById(R.id.btn_import)

        setupSpinners()
        setupTagChips()
        setupListeners()
        refreshList()
    }

    private fun setupSpinners() {
        val gpuAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, GPU_OPTIONS)
        gpuAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGpu.adapter = gpuAdapter

        val cpuAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, CPU_OPTIONS)
        cpuAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCpu.adapter = cpuAdapter

        val vramItems = VRAM_OPTIONS.map { it.first }
        val vramAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, vramItems)
        vramAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVram.adapter = vramAdapter

        val ramItems = RAM_OPTIONS.map { it.first }
        val ramAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, ramItems)
        ramAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRam.adapter = ramAdapter

        val sortItems = SORT_OPTIONS.map { it.first }
        val sortAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortItems)
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = sortAdapter
    }

    private fun setupTagChips() {
        containerTags.removeAllViews()
        TYPE_OPTIONS.forEach { tag ->
            val chip = Chip(requireContext()).apply {
                text = tag
                isCheckable = true
                isCheckedIconVisible = false
                setChipBackgroundColorResource(android.R.color.transparent)
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#14222e"))
                setTextColor(Color.parseColor("#7c95a8"))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#21d4ff"))
                        setTextColor(Color.parseColor("#001119"))
                    } else {
                        chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#14222e"))
                        setTextColor(Color.parseColor("#7c95a8"))
                    }
                }
                setOnClickListener {
                    val t = tag
                    if (selectedTypeTags.contains(t)) {
                        selectedTypeTags.remove(t)
                        isChecked = false
                    } else {
                        selectedTypeTags.add(t)
                        isChecked = true
                    }
                    refreshList()
                }
            }
            containerTags.addView(chip)
        }
    }

    private fun setupListeners() {
        btnSearch.setOnClickListener { refreshList() }
        btnClear.setOnClickListener { clearFilters() }
        btnSubmit.setOnClickListener {
            findNavController().navigate(R.id.action_BugListFragment_to_BugSubmitFragment)
        }
        btnImport.setOnClickListener { importShareCode() }

        spinnerGpu.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerCpu.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerVram.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerRam.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshList()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        switchFav.setOnCheckedChangeListener { _, _ -> refreshList() }
    }

    private fun clearFilters() {
        etSearch.setText("")
        etShareCode.setText("")
        spinnerGpu.setSelection(0)
        spinnerCpu.setSelection(0)
        spinnerVram.setSelection(0)
        spinnerRam.setSelection(0)
        spinnerSort.setSelection(0)
        switchFav.isChecked = false
        selectedTypeTags.clear()
        setupTagChips()
        refreshList()
    }

    private fun importShareCode() {
        val code = etShareCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(requireContext(), "请先粘贴分享码", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val result = repository.importBugShareCode(code)
            Toast.makeText(requireContext(), if (result.created) "导入成功" else "已更新本地条目", Toast.LENGTH_SHORT).show()
            etShareCode.setText("")
            refreshList()
            findNavController().navigate(R.id.action_BugListFragment_to_BugDetailFragment, Bundle().apply {
                putString("bugId", result.id)
            })
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshList() {
        allBugs = repository.getBugList(
            query = etSearch.text.toString().trim(),
            gpuVendor = GPU_OPTIONS[spinnerGpu.selectedItemPosition],
            cpuVendor = CPU_OPTIONS[spinnerCpu.selectedItemPosition],
            vramCapGB = VRAM_OPTIONS[spinnerVram.selectedItemPosition].second,
            ramCapGB = RAM_OPTIONS[spinnerRam.selectedItemPosition].second,
            typeTags = selectedTypeTags.toList(),
            onlyFavorites = switchFav.isChecked,
            sort = SORT_OPTIONS[spinnerSort.selectedItemPosition].second
        )

        tvEmpty.visibility = if (allBugs.isEmpty()) View.VISIBLE else View.GONE
        renderBugList()
    }

    private fun renderBugList() {
        containerBugs.removeAllViews()
        allBugs.forEach { bug ->
            containerBugs.addView(createBugCard(bug))
        }
    }

    private fun createBugCard(bug: Bug): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dpToPx()
            }
            setCardBackgroundColor(Color.parseColor("#0D1620"))
            setStrokeColor(Color.parseColor("#1B3145"))
            strokeWidth = 1.dpToPx()
            cardElevation = 0f
            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.CUT, 12.dpToPx().toFloat())
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.CUT, 12.dpToPx().toFloat())
                .build()
            setContentPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                findNavController().navigate(R.id.action_BugListFragment_to_BugDetailFragment, Bundle().apply {
                    putString("bugId", bug.id)
                })
            }
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Title row
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val titleText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = bug.title
            textSize = 17f
            setTextColor(Color.parseColor("#d8eaf2"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        titleRow.addView(titleText)

        if (bug.severity.isNotEmpty()) {
            val sevBg = when (bug.severity) {
                "high" -> "#ff4565"
                "medium" -> "#ff8a3d"
                "low" -> "#21d4ff"
                else -> "#4a6377"
            }
            val sevText = TextView(requireContext()).apply {
                text = bug.severity
                textSize = 11f
                setTextColor(Color.parseColor("#001119"))
                setBackgroundColor(Color.parseColor(sevBg))
                setPadding(4.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
            }
            titleRow.addView(sevText)
        }
        content.addView(titleRow)

        // Summary
        val summaryText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() }
            text = bug.summary
            textSize = 13f
            setTextColor(Color.parseColor("#7c95a8"))
            maxLines = 2
        }
        content.addView(summaryText)

        // Tags row
        val tagsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dpToPx() }
        }

        val keyTags = buildKeyTags(bug)
        val tagsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        keyTags.forEach { tag ->
            val tagChip = Chip(requireContext()).apply {
                text = tag
                isCheckable = false
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#14222e"))
                setTextColor(Color.parseColor("#21d4ff"))
                setTextSize(11f)
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 0f
                setPadding(4.dpToPx(), 1.dpToPx(), 6.dpToPx(), 1.dpToPx())
            }
            tagsContainer.addView(tagChip)
        }
        tagsRow.addView(tagsContainer)

        if (repository.isFavorite(bug.id)) {
            val favText = TextView(requireContext()).apply {
                text = "已收藏"
                textSize = 11f
                setTextColor(Color.parseColor("#4ade80"))
            }
            tagsRow.addView(favText)
        }
        content.addView(tagsRow)

        // Updated time
        val updatedText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dpToPx() }
            text = "更新时间：${formatDate(bug.updatedAt)}"
            textSize = 11f
            setTextColor(Color.parseColor("#4a6377"))
        }
        content.addView(updatedText)

        card.addView(content)
        return card
    }

    private fun buildKeyTags(bug: Bug): List<String> {
        val hardware = bug.hardware
        val tags = mutableListOf<String>()

        if (hardware.gpuVendors.isNotEmpty()) {
            when (hardware.gpuVendors.first()) {
                "NVIDIA" -> tags.add("N卡")
                "AMD" -> tags.add("A卡")
                "Intel" -> tags.add("I卡")
                "Any" -> tags.add("GPU不限")
                else -> tags.add(hardware.gpuVendors.first())
            }
        }

        if (hardware.vramMinGB > 0) tags.add("${hardware.vramMinGB}GB显存")
        if (hardware.ramMinGB > 0) tags.add("${hardware.ramMinGB}GB内存")

        if (hardware.cpuVendors.isNotEmpty()) {
            val cpu = hardware.cpuVendors.first()
            tags.add(if (cpu == "Any") "CPU不限" else "CPU:$cpu")
        }

        if (tags.isEmpty()) return bug.typeTags.take(3)
        return tags.take(3)
    }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        val GPU_OPTIONS = arrayOf("不限", "NVIDIA", "AMD", "Intel")
        val CPU_OPTIONS = arrayOf("不限", "Intel", "AMD")
        val VRAM_OPTIONS = arrayOf(
            "不限" to 0, "≤4GB" to 4, "6GB" to 6, "8GB" to 8,
            "10GB" to 10, "12GB" to 12, "≥16GB" to 16
        )
        val RAM_OPTIONS = arrayOf(
            "不限" to 0, "8GB" to 8, "16GB" to 16, "32GB" to 32, "64+GB" to 64
        )
        val SORT_OPTIONS = arrayOf(
            "按更新时间" to "updatedAt", "按严重程度" to "severity"
        )
        val TYPE_OPTIONS = arrayOf(
            "崩溃", "闪退", "黑屏", "卡顿/掉帧", "网络", "声音", "画面", "启动/登录", "任务/副本"
        )
    }
}
