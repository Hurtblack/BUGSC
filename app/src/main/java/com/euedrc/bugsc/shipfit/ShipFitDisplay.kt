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
    fun isSelectableComponent(vehicleName: String?): Boolean = vehicleName.isNullOrBlank()

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
}
