package com.euedrc.bugsc.shipfit

/**
 * 船配装的纯展示/筛选逻辑。无 Android 依赖，可在 JVM 单测中直接调用。
 * 由 ShipLoadoutFragment 与 ShipFitDataRepository 委托。
 */
object ShipFitDisplay {

    // ---- 类型映射 ----
    fun mapErkulTypeToUexType(t: String): String? = when (t) {
        "PowerPlant" -> "power_plant"
        "Cooler" -> "cooler"
        "Shield" -> "shield_generator"
        "QuantumDrive" -> "quantum_drive"
        "Radar" -> "radar"
        "WeaponGun" -> "weapon_gun"
        "MissileRack" -> "missile_rack"
        "MissileLauncher" -> "missile_rack"
        "Missile" -> "missile"
        "Turret" -> "turret"
        "UtilityTurret" -> "mining_laser"
        "ToolArm" -> "mining_laser"
        "MiningModule" -> "mining_module"
        "WeaponDefensive" -> "missile_rack"
        else -> null
    }

    fun categoryLabel(type: String): String = when (type) {
        "power_plant" -> "电源"
        "cooler" -> "冷却"
        "shield_generator" -> "护盾"
        "quantum_drive" -> "量子"
        "radar" -> "雷达"
        "weapon_gun" -> "武器"
        "missile_rack" -> "导弹"
        "missile" -> "导弹弹体"
        "turret" -> "炮塔"
        "mining_laser" -> "采矿头"
        "mining_module" -> "采矿模组"
        else -> type
    }

    fun powerGroupLabel(code: String): String = when (code) {
        "WPN" -> "武器"
        "SHD" -> "护盾"
        "QTM" -> "量子"
        "RDR" -> "雷达"
        "COOL" -> "冷却"
        else -> code
    }

    /** 采矿头分类的中文名，供嵌套归类比较用。 */
    val MINING_LASER_CATEGORY: String = categoryLabel("mining_laser")

    // ---- A：组件可选性（排除整船独有焊死件）----
    // 防御：Android org.json 的 optString 可能把 JSON null 返回成字符串 "null"，一并视为空。
    fun isSelectableComponent(vehicleName: String?): Boolean =
        vehicleName.isNullOrBlank() || vehicleName.equals("null", ignoreCase = true)

    // ---- 尺寸 ----
    fun formatSize(min: Int?, max: Int?): String? = when {
        min != null && max != null -> if (min == max) "S$min" else "S$min-S$max"
        min != null -> "S$min"
        max != null -> "≤S$max"
        else -> null
    }

    /** B + C：无尺寸组件不进有约束槽位；槽位 min/max 全未知时退化为 ≤S1。 */
    fun isSizeCompatible(slotMin: Int?, slotMax: Int?, compSize: Int?): Boolean {
        val hasConstraint = slotMin != null || slotMax != null
        if (compSize == null) return !hasConstraint
        val effMin = slotMin
        val effMax = if (slotMin == null && slotMax == null) 1 else slotMax
        return when {
            effMin != null && effMax != null -> compSize in effMin..effMax
            effMin != null -> compSize >= effMin
            effMax != null -> compSize <= effMax
            else -> true
        }
    }

    // ---- 方位/描述词词典 ----
    // 顺序：长词在前，避免被短词截断（如 cockpit 先于无关项；upper/lower 整词匹配）。
    private val POSITION_DICT: List<Pair<String, String>> = listOf(
        "cockpit" to "座舱",
        "remote" to "遥控",
        "center" to "中",
        "centre" to "中",
        "bottom" to "下",
        "upper" to "上",
        "lower" to "下",
        "front" to "前",
        "right" to "右",
        "rear" to "后",
        "back" to "后",
        "left" to "左",
        "main" to "主",
        "nose" to "前",
        "top" to "上",
        "mid" to "中",
    ).sortedByDescending { it.first.length }

    /** 把单个 token 贪心拆成方位/描述词的中文拼接；无法整体拆解则返回 null。 */
    private fun decomposeToken(token: String): String? {
        var rest = token
        val sb = StringBuilder()
        outer@ while (rest.isNotEmpty()) {
            for ((en, zh) in POSITION_DICT) {
                if (rest.startsWith(en)) {
                    sb.append(zh)
                    rest = rest.substring(en.length)
                    continue@outer
                }
            }
            return null // 出现非词典字符，整体判定为非方位 token
        }
        return sb.toString().ifEmpty { null }
    }

    /** 从槽位 key 解析可读方位/描述词；解析不出返回 null（交由编号兜底）。 */
    fun positionLabel(key: String): String? {
        var k = key
        listOf("hardpoint_", "fallback_", "wiki_").forEach { p ->
            if (k.startsWith(p)) k = k.removePrefix(p)
        }
        val parts = k.split('_', '-')
            .filter { it.isNotBlank() }
            .mapNotNull { decomposeToken(it.lowercase()) }
        return parts.joinToString("").ifEmpty { null }
    }

    private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")

    private fun typeLabelOf(slot: ShipSlot): String =
        slot.types.mapNotNull { mapErkulTypeToUexType(it) }
            .distinct()
            .joinToString("/") { categoryLabel(it) }

    private fun groupKeyOf(slot: ShipSlot): String =
        typeLabelOf(slot) + "|" + (formatSize(slot.minSize, slot.maxSize) ?: "")

    private fun isModuleChild(slot: ShipSlot): Boolean = slot.key.contains("/module_")

    /** 同分类内按 (类型,尺寸) 分组决定后缀：单个→无；多个且方位全可解析→方位；否则→编号。 */
    private fun suffixFor(slot: ShipSlot, categorySlots: List<ShipSlot>): String? {
        val group = categorySlots.filter { !isModuleChild(it) && groupKeyOf(it) == groupKeyOf(slot) }
        if (group.size <= 1) return null
        val positions = group.map { positionLabel(it.key) }
        val allDistinctPositions = positions.none { it == null } && positions.toSet().size == positions.size
        if (allDistinctPositions) return positionLabel(slot.key)
        val idx = group.indexOf(slot).coerceAtLeast(0)
        return if (idx < CIRCLED.size) CIRCLED[idx] else "${idx + 1}"
    }

    /** 顶级分类：排除采矿模组（已下沉到采矿头）。 */
    fun topLevelCategories(slots: List<ShipSlot>): List<String> {
        val cats = LinkedHashSet<String>()
        slots.forEach { slot ->
            if (isModuleChild(slot)) return@forEach
            slot.types.mapNotNull { mapErkulTypeToUexType(it) }.forEach { t ->
                if (t == "mining_module") return@forEach
                cats += categoryLabel(t)
            }
        }
        return cats.toList()
    }

    /** 取某分类下的槽位；采矿头分类把模组子槽位归到对应采矿头之后。 */
    fun slotsInCategory(category: String, slots: List<ShipSlot>): List<ShipSlot> {
        val matched = slots.filter { slot ->
            if (isModuleChild(slot)) {
                category == MINING_LASER_CATEGORY
            } else {
                slot.types.mapNotNull { mapErkulTypeToUexType(it) }
                    .any { categoryLabel(it) == category }
            }
        }
        if (category != MINING_LASER_CATEGORY) return matched
        val heads = matched.filter { !isModuleChild(it) }
        val modulesByParent = matched.filter { isModuleChild(it) }
            .groupBy { it.key.substringBefore("/module_") }
        return buildList {
            heads.forEach { head ->
                add(head)
                addAll(modulesByParent[head.key].orEmpty())
            }
        }
    }

    /** 槽位展示标签：类型 · 尺寸[· 方位/编号]；采矿模组子槽位缩进显示。 */
    fun slotLabel(slot: ShipSlot, categorySlots: List<ShipSlot>): String {
        if (isModuleChild(slot)) {
            val moduleNo = slot.key.substringAfter("/module_").toIntOrNull() ?: 1
            return "　模组 $moduleNo · ${categoryLabel("mining_module")}"
        }
        val base = listOfNotNull(
            typeLabelOf(slot).ifBlank { null },
            formatSize(slot.minSize, slot.maxSize),
        ).joinToString(" · ")
        val suffix = suffixFor(slot, categorySlots)
        return if (suffix != null) "$base · $suffix" else base
    }
}
