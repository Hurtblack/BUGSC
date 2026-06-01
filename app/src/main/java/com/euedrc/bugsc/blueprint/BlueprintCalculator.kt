package com.euedrc.bugsc.blueprint

/**
 * 星际公民蓝图制作 —— 成品属性计算引擎。
 *
 * 核心规则（与 SC 4.x 制作系统一致，通用算法、非任何站点专有）：
 *   成品某属性 = 基础值 × (1 + Σ 各槽位选中材料按品质插值后的修正值)
 *
 * 材料品质范围 0~1000。每种材料对某个属性的影响用一段「品质区间内的线性插值」描述。
 *
 * 这里只实现「算法」。具体「配方数据」（哪个蓝图有哪些槽、每槽能放什么材料、
 * 每种材料的修正曲线）由外部数据源填充到 [Blueprint] 中。
 */

/** 修正类型：加法型直接给增量；乘法型存的是倍率，计算时转成增量 (倍率-1)。 */
enum class ModifierType { ADDITIVE, MULTIPLICATIVE }

/**
 * 一条「材料品质 → 成品属性修正」的曲线。
 *
 * 当材料品质落在 [startQuality, endQuality] 内时，修正值在
 * [modifierAtStart, modifierAtEnd] 之间线性插值；区间外修正为 0。
 */
data class QualityModifier(
    /** 影响的成品属性 key，例如 "Weapon_Damage"。 */
    val gameplayProperty: String,
    val startQuality: Int,
    val endQuality: Int,
    val modifierAtStart: Double,
    val modifierAtEnd: Double,
    val modifierType: ModifierType,
    /** 属性中文名（可选，UEX 缺中文时用 SCM 翻译填充）。 */
    val gameplayPropertyCn: String? = null,
)

/** 某个槽位可选的一种材料（矿物等）。 */
data class MaterialOption(
    val resourceName: String,
    val resourceNameCn: String? = null,
    val standardCargoUnits: Double = 0.0,
    /** 用户未指定品质时使用的默认品质（SCM 默认 500）。 */
    val minQuality: Int = 500,
    val modifiers: List<QualityModifier> = emptyList(),
)

/** 蓝图的一个制作槽位，包含若干可选材料。 */
data class BlueprintSlot(
    val name: String,
    val nameCn: String? = null,
    val options: List<MaterialOption>,
)

/** 一个蓝图配方。 */
data class Blueprint(
    /** 分类名，决定基础属性字段含义（如 "FPSWeapons"）。 */
    val categoryName: String,
    val slots: List<BlueprintSlot>,
    /** 成品基础属性：gameplayProperty -> 基础值。 */
    val baseStats: Map<String, Double>,
)

/**
 * 用户对一个蓝图的当前选择。
 * @param optionIndexBySlot   槽位下标 -> 选中的材料下标（默认 0）
 * @param qualityBySlot       槽位下标 -> 该槽材料品质 0~1000（缺省用材料的 minQuality）
 * @param externalMultipliers 可选外部倍率（如武器配件加成）：gameplayProperty -> 倍率，默认 1.0
 */
data class CraftSelection(
    val optionIndexBySlot: Map<Int, Int> = emptyMap(),
    val qualityBySlot: Map<Int, Int> = emptyMap(),
    val externalMultipliers: Map<String, Double> = emptyMap(),
)

/** 单个属性的计算结果。 */
data class StatResult(
    val property: String,
    val propertyCn: String?,
    val baseValue: Double,
    val finalValue: Double,
) {
    val delta: Double get() = finalValue - baseValue
    /** 相对基础值的变化百分比；基础为 0 时返回 0。 */
    val deltaPercent: Double get() = if (baseValue != 0.0) (finalValue - baseValue) / baseValue * 100.0 else 0.0
}

object BlueprintCalculator {

    /**
     * 单条修正曲线在给定品质下的修正值。对应 SCM JS 中的 `$a` 函数。
     * @return 加法型返回增量；乘法型返回 (倍率 - 1)；品质越界返回 0。
     */
    fun modifierValue(m: QualityModifier, quality: Int): Double {
        if (quality < m.startQuality || quality > m.endQuality) return 0.0
        val span = m.endQuality - m.startQuality
        val ratio = if (span == 0) 1.0 else (quality - m.startQuality).toDouble() / span
        val t = ratio.coerceIn(0.0, 1.0)
        val r = m.modifierAtStart + t * (m.modifierAtEnd - m.modifierAtStart)
        return if (m.modifierType == ModifierType.ADDITIVE) r else r - 1.0
    }

    /** 取某槽当前选中的材料。对应 SCM JS 中的 `zt`。 */
    fun selectedOption(slot: BlueprintSlot, optionIndex: Int): MaterialOption =
        slot.options.getOrNull(optionIndex) ?: slot.options.first()

    /**
     * 汇总所有槽位、所有材料对某个属性的修正增量之和。对应 SCM JS 中的 `ea`。
     */
    fun aggregateDelta(blueprint: Blueprint, selection: CraftSelection, property: String): Double {
        var total = 0.0
        blueprint.slots.forEachIndexed { index, slot ->
            val option = selectedOption(slot, selection.optionIndexBySlot[index] ?: 0)
            val quality = selection.qualityBySlot[index] ?: option.minQuality
            option.modifiers.forEach { mod ->
                if (mod.gameplayProperty == property) {
                    total += modifierValue(mod, quality)
                }
            }
        }
        return total
    }

    /** 外部倍率（如配件加成），缺省 1.0。 */
    private fun externalMultiplier(selection: CraftSelection, property: String): Double =
        selection.externalMultipliers[property] ?: 1.0

    /**
     * 计算成品某属性的最终值：base × (1 + delta) × 外部倍率。
     */
    fun finalStat(blueprint: Blueprint, selection: CraftSelection, property: String): Double {
        val base = blueprint.baseStats[property] ?: 0.0
        return base * (1.0 + aggregateDelta(blueprint, selection, property)) *
            externalMultiplier(selection, property)
    }

    /**
     * DPS 特例（对应 SCM JS 的 raw_dps 分支）：
     *   dps = 基础DPS × (1 + 伤害delta) × 伤害倍率 × (1 + 射速delta) × 射速倍率
     * @param baseDpsProperty 基础 DPS 在 baseStats 中的 key，默认 "raw_dps"
     */
    fun finalDps(
        blueprint: Blueprint,
        selection: CraftSelection,
        baseDpsProperty: String = "raw_dps",
        damageProperty: String = "Weapon_Damage",
        fireRateProperty: String = "Weapon_FireRate",
    ): Double {
        val baseDps = blueprint.baseStats[baseDpsProperty] ?: return 0.0
        val dmgDelta = aggregateDelta(blueprint, selection, damageProperty)
        val frDelta = aggregateDelta(blueprint, selection, fireRateProperty)
        return baseDps *
            (1.0 + dmgDelta) * externalMultiplier(selection, damageProperty) *
            (1.0 + frDelta) * externalMultiplier(selection, fireRateProperty)
    }

    /**
     * 计算蓝图所有基础属性的最终值，便于直接渲染到图鉴/计算器界面。
     */
    fun computeAll(blueprint: Blueprint, selection: CraftSelection): List<StatResult> {
        // 收集所有出现过的属性的中文名（来自材料修正曲线）。
        val cnNames = HashMap<String, String>()
        blueprint.slots.forEach { slot ->
            slot.options.forEach { opt ->
                opt.modifiers.forEach { mod ->
                    mod.gameplayPropertyCn?.let { cnNames.putIfAbsent(mod.gameplayProperty, it) }
                }
            }
        }
        return blueprint.baseStats.keys.map { property ->
            StatResult(
                property = property,
                propertyCn = cnNames[property],
                baseValue = blueprint.baseStats[property] ?: 0.0,
                finalValue = finalStat(blueprint, selection, property),
            )
        }
    }
}
