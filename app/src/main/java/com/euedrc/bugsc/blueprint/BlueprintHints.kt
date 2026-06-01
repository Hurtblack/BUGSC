package com.euedrc.bugsc.blueprint

import org.json.JSONObject

/**
 * 蓝图配方线索(来自 SCM 的 qualityInfo)及基于它的简化计算器。
 *
 * 这是「完整曲线缺失」时的过渡方案:我们只有每个属性的修正范围 min/max,
 * 没有逐材料/逐品质曲线,因此用「成品整体品质 0~1000 在 min~max 间线性插值」近似:
 *
 *   modifier(quality) = min + (quality/1000) × (max - min)
 *   → quality=0   → min(最差)
 *   → quality=500 → 中点(对称范围时为 0,与 SCM 默认品质 500 一致)
 *   → quality=1000→ max(最好)
 *
 * 等拿到逐材料完整曲线后,改用 [BlueprintCalculator] 即可,接口语义一致。
 */
data class ModifierRange(val min: Double, val max: Double) {
    /** 给定品质(0~1000)在范围内线性插值得到的修正值(增量,如 0.12 = +12%)。 */
    fun modifierAt(quality: Int): Double {
        val t = (quality.coerceIn(0, MAX_QUALITY).toDouble()) / MAX_QUALITY
        return min + t * (max - min)
    }
}

/** 一个蓝图的配方线索。 */
data class BlueprintHint(
    val itemNameEn: String,
    val categoryName: String?,
    /** 该蓝图可改的属性,如 ["Weapon_Damage"]。 */
    val availableProperties: List<String>,
    /** 属性 -> 修正范围(min/max)。 */
    val modifierRanges: Map<String, ModifierRange>,
) {
    companion object {
        /**
         * 从 qualityInfo JSON 解析。结构示例:
         * { "type":"blueprint", "categoryName":"FPSWeapons",
         *   "modifierRanges":{ "Weapon_Damage":{"max":0.2,"min":-0.2} },
         *   "availableProperties":["Weapon_Damage"] }
         */
        fun fromJson(itemNameEn: String, json: JSONObject): BlueprintHint {
            val props = json.optJSONArray("availableProperties")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val rangesObj = json.optJSONObject("modifierRanges")
            val ranges = HashMap<String, ModifierRange>()
            if (rangesObj != null) {
                for (key in rangesObj.keys()) {
                    val r = rangesObj.optJSONObject(key) ?: continue
                    ranges[key] = ModifierRange(min = r.optDouble("min", 0.0), max = r.optDouble("max", 0.0))
                }
            }
            return BlueprintHint(
                itemNameEn = itemNameEn,
                categoryName = json.optString("categoryName").takeIf { it.isNotBlank() },
                availableProperties = props,
                modifierRanges = ranges,
            )
        }
    }
}

/** 单个属性的简化计算结果。 */
data class HintStatResult(
    val property: String,
    /** 修正增量,如 0.12 表示 +12%。 */
    val modifier: Double,
    /** 基础值(若提供)。 */
    val baseValue: Double?,
    /** 最终值 = base × (1 + modifier);未提供基础值时为 null。 */
    val finalValue: Double?,
) {
    val modifierPercent: Double get() = modifier * 100.0
}

object SimpleBlueprintCalculator {

    /**
     * 给定蓝图与整体品质,算出每个可改属性的修正增量。
     * @return 属性 -> 修正(如 +0.12)
     */
    fun computeModifiers(hint: BlueprintHint, quality: Int): Map<String, Double> =
        hint.modifierRanges.mapValues { (_, range) -> range.modifierAt(quality) }

    /**
     * 给定蓝图、品质,以及可选的基础值表,算出完整结果列表。
     * @param baseByProperty 属性 -> 成品基础值(来自 UEX);缺省则只给修正百分比。
     */
    fun compute(
        hint: BlueprintHint,
        quality: Int,
        baseByProperty: Map<String, Double> = emptyMap(),
    ): List<HintStatResult> =
        hint.modifierRanges.map { (property, range) ->
            val modifier = range.modifierAt(quality)
            val base = baseByProperty[property]
            HintStatResult(
                property = property,
                modifier = modifier,
                baseValue = base,
                finalValue = base?.let { it * (1.0 + modifier) },
            )
        }
}

private const val MAX_QUALITY = 1000
