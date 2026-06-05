package com.euedrc.bugsc.blueprint

import org.json.JSONObject

/** 数据未提供中文译名的派系兜底翻译。 */
private val FACTION_CN_FALLBACK = mapOf(
    "HolidayOrg" to "光灯节",
)

/** 任务文案里的模板占位符 → 通用中文词；未收录的占位符直接清除。 */
private val PLACEHOLDER_CN = mapOf(
    "LOCATION" to "指定地点",
    "DESTINATION" to "目的地",
    "DESTINATIONS" to "目的地",
    "TARGET" to "目标",
    "SYSTEM" to "指定星系",
    "SHIP" to "指定飞船",
    "CARGO_GRADE" to "货物",
    "OBJECTS" to "货物",
    "RANK" to "等级",
    "MULTITOOL" to "多功能工具",
)

/** 匹配 `[LOCATION]`、`[MAX_SCU]` 这类全大写模板占位符（中文方括号如 `[蓝图]` 不受影响）。 */
private val PLACEHOLDER_RE = Regex("\\[[A-Z][A-Z0-9_]*]")

/** 是否为游戏未完成数据里的占位文案（如派系名 `<= PLACEHOLDER =>`）。 */
fun String.isPlaceholderText(): Boolean =
    contains("PLACEHOLDER", ignoreCase = true) || (startsWith("<=") && endsWith("=>"))

/**
 * 把任务标题/简介里残留的英文模板占位符替换为可读中文（未收录的清除），
 * 并清理因删除占位符遗留的多余空格与标点前空格。
 */
fun cleanMissionPlaceholders(text: String): String =
    text.replace(PLACEHOLDER_RE) { m -> PLACEHOLDER_CN[m.value.trim('[', ']')] ?: "" }
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex(" ([，。！？、；：）」』])"), "$1")
        .replace(Regex("([（「『]) "), "$1")
        .trim()

/**
 * SCMDB 任务数据模型。
 *
 * 任务详情来自独立任务库 assets/blueprint/scmdb_missions.json。
 * 蓝图数据只提供任务 ID 与掉落关系，运行时再叠加到 [blueprints]。
 */

/** 任务地点（星系/星球，带中文）。 */
data class MissionLocation(
    val name: String,
    val nameCn: String?,
    val system: String?,
    val systemCn: String?,
    val planet: String?,
    val moon: String?,
    val type: String?,
) {
    val displayName: String get() = nameCn ?: name
    val displaySystem: String? get() = systemCn ?: system
    val context: String?
        get() = listOfNotNull(systemCn ?: system, planet, moon)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
}

/** 某任务能掉落的一个蓝图。 */
data class DroppedBlueprint(val nameEn: String, val dropChance: Double?)

data class MissionStanding(
    val name: String?,
    val nameCn: String?,
    val minReputation: Long?,
    val scopeDisplayName: String?,
    val scopeDisplayNameCn: String?,
) {
    val displayName: String? get() = nameCn ?: name
    val displayScope: String? get() = scopeDisplayNameCn ?: scopeDisplayName
}

data class MissionFactionReward(
    val amount: Long?,
    val factionName: String?,
    val factionNameCn: String?,
    val scopeName: String?,
    val scopeNameCn: String?,
) {
    val displayFaction: String? get() = factionNameCn ?: factionName
    val displayScope: String? get() = scopeNameCn ?: scopeName
}

data class MissionBlueprintReward(
    val poolName: String?,
    val chance: Double?,
    val trigger: String?,
)

/** 一个 SCMDB 任务详情。 */
data class RewardMission(
    val guid: String,
    val debugName: String?,
    val category: String?,
    val missionType: String?,
    val missionTypeCn: String?,
    val title: String?,
    val titleCn: String?,
    val description: String?,
    val descriptionCn: String?,
    val factionName: String?,
    val factionNameCn: String?,
    val canBeShared: Boolean?,
    val illegal: Boolean?,
    val onceOnly: Boolean?,
    val isCombat: Boolean,
    val timeToComplete: Int?,
    val rewardUec: Long?,
    val buyIn: Long?,
    val minStanding: MissionStanding?,
    val maxStanding: MissionStanding?,
    val factionRewards: List<MissionFactionReward>,
    val availableSystems: List<String>,
    val pyroRegion: List<String>,
    val rewardChance: Double?,
    val locations: List<MissionLocation>,
    val destinations: List<MissionLocation>,
    val blueprintRewards: List<MissionBlueprintReward>,
    val hasPersonalCooldown: Boolean?,
    val personalCooldownTime: Int?,
    val abandonedCooldownTime: Int?,
    val maxPlayersPerInstance: Int?,
    val availableInPrison: Boolean?,
    val canReacceptAfterAbandoning: Boolean?,
    val canReacceptAfterFailing: Boolean?,
    val blueprints: List<DroppedBlueprint>,
) {
    /** 标题优先中文（已洗占位），回退英文模板，再回退类型。 */
    val displayTitle: String
        get() = (titleCn?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: missionTypeCn ?: missionType ?: "未知任务")
            .let(::cleanMissionPlaceholders)

    val typeLabel: String? get() = missionTypeCn ?: missionType
    val displayFaction: String?
        get() = factionNameCn?.takeIf { it.isNotBlank() }
            ?: factionName?.takeIf { it.isNotBlank() && !it.isPlaceholderText() }
                ?.let { FACTION_CN_FALLBACK[it] ?: it }
    val displayDescription: String?
        get() = (descriptionCn?.takeIf { it.isNotBlank() } ?: description)
            ?.let(::cleanMissionPlaceholders)
            ?.takeIf { it.isNotBlank() }
    val displaySystems: String
        get() = availableSystems
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
            .ifBlank { "未知星系" }
    val locationNames: List<String>
        get() = (locations + destinations)
            .flatMap { listOfNotNull(it.nameCn, it.name, it.systemCn, it.system, it.planet, it.moon) }
            .filter { it.isNotBlank() }
            .distinct()
    val searchTokens: String
        get() = buildString {
            listOfNotNull(
                displayTitle,
                title,
                missionTypeCn,
                missionType,
                factionNameCn,
                factionName,
                displaySystems,
                debugName,
            ).forEach {
                append(it)
                append('\n')
            }
            locationNames.forEach {
                append(it)
                append('\n')
            }
        }
    val cardSecondaryLine: String
        get() = listOfNotNull(
            displayFaction?.takeIf { it.isNotBlank() },
            displaySystems.takeIf { it.isNotBlank() },
            typeLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" / ")
    val cardTertiaryBadges: List<String>
        get() = buildList {
            rewardUec?.let { add("${java.text.NumberFormat.getInstance(java.util.Locale.US).format(it)} aUEC") }
            if (isCombat) add("战斗")
            if (illegal == true) add("非法")
            if (canBeShared == true) add("可共享")
            if (onceOnly == true) add("一次性")
            if (blueprints.isNotEmpty()) add("蓝图×${blueprints.size}")
        }

    companion object {
        fun fromJson(guid: String, m: JSONObject, blueprints: List<DroppedBlueprint> = emptyList()): RewardMission {
            fun stringList(name: String): List<String> {
                val arr = m.optJSONArray(name) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            fun parseLocations(name: String): List<MissionLocation> {
                val arr = m.optJSONArray(name) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { l ->
                        val locName = l.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MissionLocation(
                            name = locName,
                            nameCn = l.optString("nameCn").takeIf { it.isNotBlank() },
                            system = l.optString("system").takeIf { it.isNotBlank() },
                            systemCn = l.optString("systemCn").takeIf { it.isNotBlank() },
                            planet = l.optString("planet").takeIf { it.isNotBlank() },
                            moon = l.optString("moon").takeIf { it.isNotBlank() },
                            type = l.optString("type").takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
            fun standing(name: String): MissionStanding? {
                val o = m.optJSONObject(name) ?: return null
                return MissionStanding(
                    name = o.optString("name").takeIf { it.isNotBlank() },
                    nameCn = o.optString("nameCn").takeIf { it.isNotBlank() },
                    minReputation = if (o.has("minReputation") && !o.isNull("minReputation")) o.optLong("minReputation") else null,
                    scopeDisplayName = o.optString("scopeDisplayName").takeIf { it.isNotBlank() },
                    scopeDisplayNameCn = o.optString("scopeDisplayNameCn").takeIf { it.isNotBlank() },
                )
            }
            val factionRewards = m.optJSONArray("factionRewards")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { r ->
                        MissionFactionReward(
                            amount = if (r.has("amount") && !r.isNull("amount")) r.optLong("amount") else null,
                            factionName = r.optString("factionName").takeIf { it.isNotBlank() },
                            factionNameCn = r.optString("factionNameCn").takeIf { it.isNotBlank() },
                            scopeName = r.optString("scopeName").takeIf { it.isNotBlank() },
                            scopeNameCn = r.optString("scopeNameCn").takeIf { it.isNotBlank() },
                        )
                    }
                }
            } ?: emptyList()
            val blueprintRewards = m.optJSONArray("blueprintRewards")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { r ->
                        MissionBlueprintReward(
                            poolName = r.optString("poolName").takeIf { it.isNotBlank() },
                            chance = if (r.has("chance") && !r.isNull("chance")) r.optDouble("chance") else null,
                            trigger = r.optString("trigger").takeIf { it.isNotBlank() },
                        )
                    }
                }
            } ?: emptyList()
            return RewardMission(
                guid = guid,
                debugName = m.optString("debugName").takeIf { it.isNotBlank() },
                category = m.optString("category").takeIf { it.isNotBlank() },
                missionType = m.optString("missionType").takeIf { it.isNotBlank() },
                missionTypeCn = m.optString("missionTypeCn").takeIf { it.isNotBlank() },
                title = m.optString("title").takeIf { it.isNotBlank() },
                titleCn = m.optString("titleCn").takeIf { it.isNotBlank() },
                description = m.optString("description").takeIf { it.isNotBlank() },
                descriptionCn = m.optString("descriptionCn").takeIf { it.isNotBlank() },
                factionName = m.optString("factionName").takeIf { it.isNotBlank() },
                factionNameCn = m.optString("factionNameCn").takeIf { it.isNotBlank() },
                canBeShared = if (m.has("canBeShared") && !m.isNull("canBeShared")) m.optBoolean("canBeShared") else null,
                illegal = if (m.has("illegal") && !m.isNull("illegal")) m.optBoolean("illegal") else null,
                onceOnly = if (m.has("onceOnly") && !m.isNull("onceOnly")) m.optBoolean("onceOnly") else null,
                isCombat = m.optBoolean("isCombat", false),
                timeToComplete = if (m.has("timeToComplete") && !m.isNull("timeToComplete")) m.optInt("timeToComplete") else null,
                rewardUec = if (m.has("rewardUec") && !m.isNull("rewardUec")) m.optLong("rewardUec") else null,
                buyIn = if (m.has("buyIn") && !m.isNull("buyIn")) m.optLong("buyIn") else null,
                minStanding = standing("minStanding"),
                maxStanding = standing("maxStanding"),
                factionRewards = factionRewards,
                availableSystems = stringList("availableSystems"),
                pyroRegion = stringList("pyroRegion"),
                rewardChance = if (m.has("rewardChance") && !m.isNull("rewardChance")) m.optDouble("rewardChance") else null,
                locations = parseLocations("locations"),
                destinations = parseLocations("destinations"),
                blueprintRewards = blueprintRewards,
                hasPersonalCooldown = if (m.has("hasPersonalCooldown") && !m.isNull("hasPersonalCooldown")) m.optBoolean("hasPersonalCooldown") else null,
                personalCooldownTime = if (m.has("personalCooldownTime") && !m.isNull("personalCooldownTime")) m.optInt("personalCooldownTime") else null,
                abandonedCooldownTime = if (m.has("abandonedCooldownTime") && !m.isNull("abandonedCooldownTime")) m.optInt("abandonedCooldownTime") else null,
                maxPlayersPerInstance = if (m.has("maxPlayersPerInstance") && !m.isNull("maxPlayersPerInstance")) m.optInt("maxPlayersPerInstance") else null,
                availableInPrison = if (m.has("availableInPrison") && !m.isNull("availableInPrison")) m.optBoolean("availableInPrison") else null,
                canReacceptAfterAbandoning = if (m.has("canReacceptAfterAbandoning") && !m.isNull("canReacceptAfterAbandoning")) m.optBoolean("canReacceptAfterAbandoning") else null,
                canReacceptAfterFailing = if (m.has("canReacceptAfterFailing") && !m.isNull("canReacceptAfterFailing")) m.optBoolean("canReacceptAfterFailing") else null,
                blueprints = blueprints,
            )
        }
    }
}

fun RewardMission.matchesMissionQuery(
    query: String,
    selectedSystem: String,
    selectedFaction: String,
): Boolean {
    val normalizedQuery = query.trim().lowercase()
    val queryOk = normalizedQuery.isEmpty() || searchTokens.lowercase().contains(normalizedQuery)
    val systemOk = selectedSystem == "__all__" ||
        availableSystems.any { it.equals(selectedSystem, ignoreCase = true) }
    val factionOk = selectedFaction == "__all__" ||
        displayFaction.equals(selectedFaction, ignoreCase = true) ||
        factionName.equals(selectedFaction, ignoreCase = true) ||
        factionNameCn.equals(selectedFaction, ignoreCase = true)
    return queryOk && systemOk && factionOk
}
