package com.euedrc.bugsc.blueprint

import org.json.JSONObject

/** 列表页轻量索引条目（不含完整槽位曲线，仅用于搜索/显示）。 */
data class ScCraftIndexEntry(
    val nameEn: String,
    val category: String,
    val craftTimeSeconds: Int,
    val missionCount: Int,
    /** 该蓝图所有槽位的材料英文名（含 allOptions 选项），用于材料反查搜索。 */
    val materials: List<String> = emptyList(),
)

/**
 * sc-craft.tools 格式的蓝图数据模型。
 *
 * 数据来源：gen_sccraft_blueprints.py 抓取 sc-craft.tools 公开 API，
 * 存储在 assets/blueprint/sccraft_blueprints.json。
 *
 * 与 [BlueprintCalculator] 的桥接：[toBlueprint] 将此模型转换为计算引擎可用的 [Blueprint]。
 */

/** 一个槽位对某属性的品质修正曲线。 */
data class ScCraftEffect(
    val stat: String,          // 英文属性名，例如 "Integrity"、"Impact Force"
    val statLocKey: String,    // 游戏本地化键，例如 "statname_gpp_health_maxhealth"
    val qualityMin: Int,       // 生效品质下限（通常 0）
    val qualityMax: Int,       // 生效品质上限（通常 1000）
    val modifierAtMin: Double, // 品质=qualityMin 时的倍率（例如 0.9 = 减少10%）
    val modifierAtMax: Double, // 品质=qualityMax 时的倍率（例如 1.1 = 增加10%）
) {
    /** 给定品质 [quality] 时的倍率（不减1的原始倍率）。 */
    fun multiplierAt(quality: Int): Double {
        if (qualityMin == qualityMax) return modifierAtMin
        val q = quality.coerceIn(qualityMin, qualityMax)
        val t = (q - qualityMin).toDouble() / (qualityMax - qualityMin)
        return modifierAtMin + t * (modifierAtMax - modifierAtMin)
    }

    /**
     * 给定品质时的修正增量：
     * - 常规属性：源值是倍率，返回 multiplier - 1（例如 1.1 -> +0.1）
     * - 部分离散属性（如 Power Pips）：源值就是绝对增减档位（例如 -1/0/+1）
     */
    fun deltaAt(quality: Int): Double {
        if (quality < qualityMin || quality > qualityMax) return 0.0
        val value = multiplierAt(quality)
        return if (isAbsoluteStepStat()) value else value - 1.0
    }

    /** 格式化显示：±X.X% 形式。 */
    fun formatDelta(quality: Int): String {
        val d = deltaAt(quality) * 100.0
        return if (d >= 0) "+%.1f%%".format(d) else "%.1f%%".format(d)
    }

    fun isAbsoluteStepStat(): Boolean {
        return statLocKey == "statname_gpp_itemresource_powergeneration"
    }
}

/** 一个材料选项（通常每槽只有一种）。 */
data class ScCraftOption(
    val name: String,
    val locKey: String,
    val quantityScu: Double,
    val minQuality: Int,      // 游戏规定的最低可接受品质
)

/** 蓝图的一个制作槽位。 */
data class ScCraftSlot(
    val slot: String,           // 槽位标识，例如 "FRAME"
    val slotLocKey: String,     // 游戏键，例如 "crafting_ui_slotname_frame"
    val material: String,       // 默认材料英文名，例如 "Iron"
    val materialLocKey: String, // 例如 "items_commodities_iron"
    val quantityScu: Double,
    val minQuality: Int,
    val qualityEffects: List<ScCraftEffect>,
    val allOptions: List<ScCraftOption> = emptyList(), // 多选项槽位
)

/** 蓝图可通过完成的任务奖励获取。 */
data class ScCraftMission(
    val missionId: Long,
    val name: String,
    val dropChance: Double,
)

/** 一个完整蓝图（sc-craft.tools 格式）。 */
data class ScCraftBlueprint(
    val nameEn: String,
    val blueprintId: String,
    val category: String,
    val craftTimeSeconds: Int,
    val tiers: Int,
    val slots: List<ScCraftSlot>,
    val missions: List<ScCraftMission>,
) {
    val hasMissions: Boolean get() = missions.isNotEmpty()

    /**
     * 计算所有槽位在给定品质下的属性修正增量汇总。
     * @param qualityBySlot 槽位标识 → 品质 0-1000；缺省使用 500
     * @return stat → 总修正增量（可直接用于 finalValue = base × (1+delta)）
     */
    fun aggregateDeltas(qualityBySlot: Map<String, Int> = emptyMap()): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (slot in slots) {
            val quality = qualityBySlot[slot.slot] ?: 0
            for (effect in slot.qualityEffects) {
                result[effect.stat] = (result[effect.stat] ?: 0.0) + effect.deltaAt(quality)
            }
        }
        return result
    }

    /**
     * 转换为 [BlueprintCalculator] 所用的 [Blueprint]。
     * [baseStats] 由外部提供（UEX API 或图鉴数据），若无基础值则只能算修正百分比。
     */
    fun toBlueprint(baseStats: Map<String, Double> = emptyMap()): Blueprint {
        val bpSlots = slots.map { slot ->
            BlueprintSlot(
                name = slot.slot,
                nameCn = null,
                options = listOf(
                    MaterialOption(
                        resourceName = slot.material,
                        resourceNameCn = null,
                        standardCargoUnits = slot.quantityScu,
                        minQuality = 500, // 默认品质居中
                        modifiers = slot.qualityEffects.map { e ->
                            QualityModifier(
                                gameplayProperty = e.stat,
                                startQuality = e.qualityMin,
                                endQuality = e.qualityMax,
                                modifierAtStart = e.modifierAtMin,
                                modifierAtEnd = e.modifierAtMax,
                                modifierType = ModifierType.MULTIPLICATIVE,
                            )
                        },
                    )
                ),
            )
        }
        return Blueprint(
            categoryName = category,
            slots = bpSlots,
            baseStats = baseStats,
        )
    }

    companion object {
        fun fromJson(nameEn: String, json: JSONObject): ScCraftBlueprint {
            val slotsArr = json.optJSONArray("slots")
            val slots = if (slotsArr == null) emptyList() else {
                (0 until slotsArr.length()).mapNotNull { i ->
                    slotsArr.optJSONObject(i)?.let { s ->
                        val effectsArr = s.optJSONArray("qualityEffects")
                        val effects = if (effectsArr == null) emptyList() else {
                            (0 until effectsArr.length()).mapNotNull { j ->
                                effectsArr.optJSONObject(j)?.let { e ->
                                    val loc = e.optString("statLocKey")
                                    val rawMin = e.optDouble("modifierAtMin", 1.0)
                                    val rawMax = e.optDouble("modifierAtMax", 1.0)
                                    // sc-craft.tools 数据 bug：护甲减伤某些条目把 0.9 写成了 9
                                    fun sanitize(v: Double) =
                                        if (loc == "statname_gpp_armor_damagemitigation" && v > 5.0) v / 10.0 else v
                                    ScCraftEffect(
                                        stat = e.optString("stat"),
                                        statLocKey = loc,
                                        qualityMin = e.optInt("qualityMin", 0),
                                        qualityMax = e.optInt("qualityMax", 1000),
                                        modifierAtMin = sanitize(rawMin),
                                        modifierAtMax = sanitize(rawMax),
                                    )
                                }
                            }
                        }
                        val optionsArr = s.optJSONArray("allOptions")
                        val allOptions = if (optionsArr == null) emptyList() else {
                            (0 until optionsArr.length()).mapNotNull { j ->
                                optionsArr.optJSONObject(j)?.let { o ->
                                    ScCraftOption(
                                        name = o.optString("name"),
                                        locKey = o.optString("locKey"),
                                        quantityScu = o.optDouble("quantityScu", 0.0),
                                        minQuality = o.optInt("minQuality", 1),
                                    )
                                }
                            }
                        }
                        ScCraftSlot(
                            slot = s.optString("slot"),
                            slotLocKey = s.optString("slotLocKey"),
                            material = s.optString("material"),
                            materialLocKey = s.optString("materialLocKey"),
                            quantityScu = s.optDouble("quantityScu", 0.0),
                            minQuality = s.optInt("minQuality", 1),
                            qualityEffects = effects,
                            allOptions = allOptions,
                        )
                    }
                }
            }
            val missionsArr = json.optJSONArray("missions")
            val missions = if (missionsArr == null) emptyList() else {
                (0 until missionsArr.length()).mapNotNull { i ->
                    missionsArr.optJSONObject(i)?.let { m ->
                        ScCraftMission(
                            missionId = m.optLong("missionId"),
                            name = m.optString("name"),
                            dropChance = m.optDouble("dropChance", 1.0),
                        )
                    }
                }
            }
            return ScCraftBlueprint(
                nameEn = nameEn,
                blueprintId = json.optString("blueprintId"),
                category = json.optString("category"),
                craftTimeSeconds = json.optInt("craftTimeSeconds", 0),
                tiers = json.optInt("tiers", 1),
                slots = slots,
                missions = missions,
            )
        }
    }
}
