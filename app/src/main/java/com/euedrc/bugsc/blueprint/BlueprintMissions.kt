package com.euedrc.bugsc.blueprint

import org.json.JSONObject

/**
 * 蓝图「通过任务获取」数据模型（来自 flowcld SCM 的 reward-mission-details 公开接口）。
 *
 * 数据由维护脚本 tools/gen_blueprint_missions.py 一次性导出到
 * assets/blueprint/scm_blueprint_missions.json，运行时零依赖 SCM。
 *
 * 范围为「中档卡」：类型 / 赏金 / 地点 / 掉率 / 该任务掉落的蓝图列表。
 * 派系·声望·时限·冷却·合法性标签在 flowcld 登录墙后，此处不抓。
 *
 * 注意：titleCn 里的 ~mission(xxx) / [SHIP] 是运行时变量，导出脚本已洗成
 * 「舰船」「地点」等可读占位 —— 不存在唯一正确值，卡片价值在结构化字段。
 */

/** 任务地点（星系/星球，带中文）。 */
data class MissionLocation(
    val name: String,
    val nameCn: String?,
    val system: String?,
    val systemCn: String?,
    val type: String?,
) {
    val displayName: String get() = nameCn ?: name
    val displaySystem: String? get() = systemCn ?: system
}

/** 某任务能掉落的一个蓝图。 */
data class DroppedBlueprint(val nameEn: String, val dropChance: Double?)

/** 一个奖励任务的完整中档详情。 */
data class RewardMission(
    val guid: String,
    val missionType: String?,
    val missionTypeCn: String?,
    val title: String?,
    val titleCn: String?,
    val isCombat: Boolean,
    val rewardUec: Long?,
    val rewardChance: Double?,
    val locations: List<MissionLocation>,
    val blueprints: List<DroppedBlueprint>,
) {
    /** 标题优先中文（已洗占位），回退英文模板，再回退类型。 */
    val displayTitle: String
        get() = titleCn?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: missionTypeCn ?: missionType ?: "未知任务"

    val typeLabel: String? get() = missionTypeCn ?: missionType

    companion object {
        fun fromJson(guid: String, m: JSONObject, missionTypes: Map<String, String>): RewardMission {
            val type = m.optString("missionType").takeIf { it.isNotBlank() }
            val locations = m.optJSONArray("locations")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { l ->
                        val name = l.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MissionLocation(
                            name = name,
                            nameCn = l.optString("nameCn").takeIf { it.isNotBlank() },
                            system = l.optString("system").takeIf { it.isNotBlank() },
                            systemCn = l.optString("systemCn").takeIf { it.isNotBlank() },
                            type = l.optString("type").takeIf { it.isNotBlank() },
                        )
                    }
                }
            } ?: emptyList()
            val blueprints = m.optJSONArray("blueprints")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { b ->
                        val nameEn = b.optString("nameEn").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        DroppedBlueprint(
                            nameEn = nameEn,
                            dropChance = if (b.has("dropChance") && !b.isNull("dropChance")) b.optDouble("dropChance") else null,
                        )
                    }
                }
            } ?: emptyList()
            return RewardMission(
                guid = guid,
                missionType = type,
                missionTypeCn = type?.let { missionTypes[it] },
                title = m.optString("title").takeIf { it.isNotBlank() },
                titleCn = m.optString("titleCn").takeIf { it.isNotBlank() },
                isCombat = m.optBoolean("isCombat", false),
                rewardUec = if (m.has("rewardUec") && !m.isNull("rewardUec")) m.optLong("rewardUec") else null,
                rewardChance = if (m.has("rewardChance") && !m.isNull("rewardChance")) m.optDouble("rewardChance") else null,
                locations = locations,
                blueprints = blueprints,
            )
        }
    }
}
