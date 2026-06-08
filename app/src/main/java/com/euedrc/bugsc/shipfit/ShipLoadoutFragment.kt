package com.euedrc.bugsc.shipfit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.euedrc.bugsc.databinding.FragmentShipLoadoutBinding

class ShipLoadoutFragment : Fragment() {

    private var _binding: FragmentShipLoadoutBinding? = null
    private val binding get() = _binding!!

    private var ships: List<ShipCard> = emptyList()
    private var components: List<FitComponent> = emptyList()
    private var currentShip: ShipCard? = null
    private var currentSlots: List<ShipSlot> = emptyList()
    private var categorySlots: List<ShipSlot> = emptyList()
    private var categories: List<String> = emptyList()
    private var currentCategory: String? = null
    private var currentSlot: ShipSlot? = null
    private var filteredComponents: List<FitComponent> = emptyList()
    private var componentsById: Map<String, FitComponent> = emptyMap()
    private var lastPowerSummary: PowerSummary? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShipLoadoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val shipId = arguments?.getString("shipId").orEmpty()
        val repo = ShipFitDataRepository(requireContext())
        ships = repo.loadShips()
        components = repo.loadComponents()
        componentsById = components.associateBy { it.id }
        currentShip = ships.firstOrNull { it.id == shipId }
        val ship = currentShip
        if (ship == null) {
            toast("找不到船只：$shipId")
            return
        }
        binding.tvShipName.text = ship.zhName?.let { "$it (${ship.name})" } ?: ship.name
        currentSlots = ship.slots
        refreshSlots()
        applyDefaultTemplate()

        binding.powerGrid.setOnAllocationChanged { allocations ->
            val summary = lastPowerSummary ?: return@setOnAllocationChanged
            binding.tvPowerGroups.text = summary.groups.mapIndexed { index, group ->
                "${ShipFitDisplay.powerGroupLabel(group.first)} ${allocations.getOrNull(index) ?: 0}/${group.second}"
            }.joinToString(" | ")
        }
        binding.btnAddSlot.setOnClickListener { addSlotConfig() }
        binding.btnResetDefault.setOnClickListener { applyDefaultTemplate() }
        binding.btnGenerate.setOnClickListener { generateCode() }
        binding.btnDecode.setOnClickListener { decodeCode() }
        binding.btnCopyCode.setOnClickListener { copyCode() }
        binding.btnPasteCode.setOnClickListener { pasteCode() }
    }

    private fun refreshSlots() {
        val selectedCategory = currentCategory
        val allSlots = effectiveSlots()
        categories = ShipFitDisplay.topLevelCategories(allSlots)
        binding.spCategory.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            categories
        )
        currentCategory = selectedCategory?.takeIf { it in categories } ?: categories.firstOrNull()
        refreshSlotsByCategory()
        updatePowerPanel(parseSlots(binding.etSlots.text?.toString().orEmpty()))
        binding.spCategory.setOnItemSelectedListener(SimpleItemSelectedListener { pos ->
            currentCategory = categories.getOrNull(pos)
            refreshSlotsByCategory()
        })
        binding.spSlot.setOnItemSelectedListener(SimpleItemSelectedListener { pos ->
            currentSlot = categorySlots.getOrNull(pos)
            refreshComponents()
        })
    }

    private fun refreshSlotsByCategory() {
        val cat = currentCategory ?: return
        categorySlots = ShipFitDisplay.slotsInCategory(cat, effectiveSlots())
        val slotLabels = categorySlots.map { ShipFitDisplay.slotLabel(it, categorySlots) }
        val singleSlot = categorySlots.size == 1
        val slotVisibility = if (singleSlot) View.GONE else View.VISIBLE
        binding.tvStepSlot.visibility = slotVisibility
        binding.spSlot.visibility = slotVisibility
        binding.spSlot.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            slotLabels.ifEmpty { listOf("该分类暂无槽位") }
        )
        currentSlot = categorySlots.firstOrNull()
        refreshComponents()
    }

    private fun refreshComponents() {
        val slot = currentSlot ?: return
        val acceptedTypes = slot.types.mapNotNull { ShipFitDisplay.mapErkulTypeToUexType(it) }.toSet()
        filteredComponents = components.filter { c ->
            c.type in acceptedTypes && ShipFitDisplay.isSizeCompatible(slot.minSize, slot.maxSize, c.size)
        }.sortedWith(compareBy<FitComponent> { componentSortName(it) }.thenBy { it.name.lowercase() })
        val labels = filteredComponents.map { "${componentDisplayName(it)} [${ShipFitDisplay.categoryLabel(it.type)}] S${it.size ?: "?"}" }
        binding.spComponent.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels.ifEmpty { listOf("无可装组件") }
        )
    }

    private fun applyDefaultTemplate() {
        val ship = currentShip ?: return
        val map = linkedMapOf<String, String>()
        val byType = components.groupBy { it.type }
        currentSlots.forEach { slot ->
            val candidates = slot.types
                .mapNotNull { ShipFitDisplay.mapErkulTypeToUexType(it) }
                .distinct()
                .flatMap { t -> byType[t].orEmpty().filter { ShipFitDisplay.isSizeCompatible(slot.minSize, slot.maxSize, it.size) } }
                .distinctBy { it.id }
                .sortedWith(compareBy<FitComponent> { it.size ?: 999 }.thenBy { it.name.lowercase() })
            val best = candidates.firstOrNull()
            if (best != null) map[slot.key] = best.id
        }
        binding.etSlots.setText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        updatePowerPanel(map)
        renderSlotSummary(map)
        refreshSlots()
        binding.tvResult.text = "已加载默认模板：${ship.name}（${map.size} 个槽位）"
    }

    private fun addSlotConfig() {
        val slot = currentSlot ?: return
        if (filteredComponents.isEmpty()) {
            toast("当前槽位没有可装组件")
            return
        }
        val idx = binding.spComponent.selectedItemPosition.coerceAtLeast(0)
        val comp = filteredComponents.getOrNull(idx) ?: return
        val map = parseSlots(binding.etSlots.text?.toString().orEmpty())
        map[slot.key] = comp.id
        binding.etSlots.setText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        updatePowerPanel(map)
        renderSlotSummary(map)
        if (comp.type == "mining_laser") refreshSlots()
    }

    private fun generateCode() {
        val ship = currentShip?.id ?: return
        val slots = parseSlots(binding.etSlots.text?.toString().orEmpty())
        if (slots.isEmpty()) {
            toast("请至少填写一个槽位")
            return
        }
        val code = ShipFitCodec.encode(ShipFitPayload(ship = ship, slots = slots))
        binding.etCode.setText(code)
        binding.tvResult.text = "已生成配船码，共 ${slots.size} 个槽位"
    }

    private fun decodeCode() {
        val code = binding.etCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            toast("请先输入配船码")
            return
        }
        when (val result = ShipFitCodec.decode(code)) {
            is DecodeResult.Success -> {
                val text = result.payload.slots.entries.joinToString("\n") { "${it.key}=${it.value}" }
                binding.etSlots.setText(text)
                binding.tvResult.text = "解析成功：${result.payload.ship}，${result.payload.slots.size} 个槽位"
                updatePowerPanel(result.payload.slots)
                renderSlotSummary(result.payload.slots)
            }
            is DecodeResult.Error -> {
                binding.tvResult.text = "解析失败 [${result.code}] ${result.message}"
            }
        }
    }

    private fun copyCode() {
        val code = binding.etCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            toast("没有可复制的配船码")
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BUGFIT", code))
        toast("已复制配船码")
    }

    private fun pasteCode() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isBlank()) {
            toast("剪贴板为空")
            return
        }
        binding.etCode.setText(text.trim())
    }

    private fun updatePowerPanel(slotsMap: Map<String, String>) {
        val p = ShipPowerCalculator.calculate(
            slotsMap = slotsMap,
            componentsById = componentsById,
        )
        binding.tvPowerHead.text = "输出 %.1f · 已用 %.1f · 剩余 %d / %d 格"
            .format(p.output, p.consumption, p.spareSegments, p.totalSegments)
        binding.pbPowerRatio.progress = p.ratio.coerceAtMost(100)
        binding.tvPowerRatio.text = "总耗电 ${p.ratio}% · 已占 ${p.usedSegments} 格"
        lastPowerSummary = p
        binding.powerGrid.setSummary(p)
        binding.tvPowerGroups.text = p.groups.joinToString(" | ") { "${ShipFitDisplay.powerGroupLabel(it.first)} 需求${it.second}" }
        renderSlotSummary(slotsMap)
    }

    private fun renderSlotSummary(slotsMap: Map<String, String>) {
        if (slotsMap.isEmpty()) {
            binding.tvSlotSummary.text = "当前配置：暂无组件"
            return
        }
        val allSlotsByKey = effectiveSlots().associateBy { it.key }
        binding.tvSlotSummary.text = buildString {
            append("当前配置（中文优先）\n")
            slotsMap.forEach { (slotKey, componentId) ->
                val slot = allSlotsByKey[slotKey]
                val component = componentsById[componentId]
                val label = slot?.let { ShipFitDisplay.slotLabel(it, effectiveSlots()) } ?: slotKey
                val name = component?.let { componentDisplayName(it) } ?: componentId
                append(label).append(" = ").append(name).append('\n')
            }
        }.trimEnd()
    }

    private fun effectiveSlots(): List<ShipSlot> {
        val selected = parseSlots(binding.etSlots.text?.toString().orEmpty())
        if (selected.isEmpty()) return currentSlots
        return currentSlots + miningModuleSlots(selected)
    }

    private fun miningModuleSlots(selected: Map<String, String>): List<ShipSlot> {
        val out = mutableListOf<ShipSlot>()
        currentSlots.forEach { slot ->
            val componentId = selected[slot.key] ?: return@forEach
            val component = componentsById[componentId] ?: return@forEach
            if (component.type != "mining_laser") return@forEach
            repeat(miningModuleSlotCount(component.name)) { idx ->
                out += ShipSlot(
                    key = "${slot.key}/module_${idx + 1}",
                    minSize = null,
                    maxSize = null,
                    types = listOf("MiningModule"),
                )
            }
        }
        return out
    }

    private fun miningModuleSlotCount(name: String): Int {
        val key = name.lowercase()
        return when {
            "arbor mhv" in key -> 0
            "arbor mh1" in key -> 1
            "arbor mh2" in key -> 2
            "helix ii" in key -> 3
            "helix i" in key -> 2
            "hofstede-s1" in key -> 1
            "hofstede-s2" in key -> 2
            "impact ii" in key -> 3
            "impact i" in key -> 2
            "klein-s1" in key -> 0
            "klein-s2" in key -> 1
            "lancet mh1" in key -> 1
            "lancet mh2" in key -> 2
            "lawson" in key -> 0
            "pitman" in key -> 2
            "s0 helix" in key -> 0
            "s00 hofstede" in key -> 0
            else -> 0
        }
    }

    private fun parseSlots(raw: String): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0 || idx >= line.length - 1) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) map[key] = value
        }
        return map
    }

    private fun componentDisplayName(c: FitComponent): String {
        val zh = c.zhName?.trim().orEmpty()
        return if (zh.isBlank()) c.name else "$zh (${c.name})"
    }

    private fun componentSortName(c: FitComponent): String =
        c.zhName?.trim()?.takeIf { it.isNotBlank() } ?: c.name

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected(position)
    }
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
