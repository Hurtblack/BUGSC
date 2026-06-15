package com.euedrc.bugsc.agent

import android.content.Context
import com.euedrc.bugsc.blueprint.BlueprintDataRepository
import com.euedrc.bugsc.mining.MiningRepository
import com.euedrc.bugsc.shipfit.ShipCard
import com.euedrc.bugsc.shipfit.ShipFitDataRepository
import com.euedrc.bugsc.wikelo.WikeloRepository

class LocalAgentDataProvider(context: Context) : AgentSearchProvider {
    private val appContext = context.applicationContext

    private val ships: List<ShipCard> by lazy { ShipFitDataRepository(appContext).loadShips() }
    private val mining: MiningRepository by lazy { MiningRepository.get(appContext) }
    private val blueprint: BlueprintDataRepository by lazy { BlueprintDataRepository(appContext) }
    private val wikelo: WikeloRepository by lazy { WikeloRepository.get(appContext) }
    private val cachedEntityIndex: AgentEntityIndex by lazy { buildEntityIndex() }

    fun entityIndex(): AgentEntityIndex = cachedEntityIndex

    private fun buildEntityIndex(): AgentEntityIndex {
        val entries = buildList {
            ships.take(250).forEach { ship ->
                add(
                    AgentEntity(
                        type = "ship",
                        value = ship.id,
                        displayName = ship.zhName ?: ship.name,
                        aliases = listOfNotNull(ship.id, ship.name, ship.zhName),
                    ),
                )
            }
            mining.elements.values.take(250).forEach { element ->
                add(
                    AgentEntity(
                        type = "mining_element",
                        value = element.guid,
                        displayName = element.displayName,
                        aliases = listOfNotNull(element.nameEn, element.nameCn),
                    ),
                )
            }
            blueprint.loadScCraftIndex().take(250).forEach { item ->
                add(
                    AgentEntity(
                        type = "blueprint",
                        value = item.nameEn,
                        displayName = item.nameEn,
                        aliases = listOf(item.nameEn) + item.materials.take(8),
                    ),
                )
            }
            wikelo.allTrades().take(250).forEach { trade ->
                add(
                    AgentEntity(
                        type = "wikelo_trade",
                        value = trade.nameEn,
                        displayName = trade.nameCn,
                        aliases = listOfNotNull(trade.nameCn, trade.nameEn, trade.rewardItem),
                    ),
                )
            }
        }
        return AgentEntityIndex(entries)
    }

    override suspend fun search(query: AgentQuery): List<AgentSearchHit> {
        val intent = query.intents.firstOrNull()?.intent ?: AgentIntent.UNKNOWN
        return when (intent) {
            AgentIntent.SHIP_INFO -> searchShips(query)
            AgentIntent.MINING -> searchMining(query)
            AgentIntent.BLUEPRINT -> searchBlueprints(query)
            AgentIntent.MISSION -> searchMissions(query)
            AgentIntent.WIKELO -> searchWikelo(query)
            else -> emptyList()
        }
    }

    private fun searchShips(query: AgentQuery): List<AgentSearchHit> =
        ships.asSequence()
            .map { it to matchScore(query, it.name, it.zhName, it.id) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { (ship, score) ->
                AgentSearchHit(
                    summary = buildString {
                        append(ship.zhName ?: ship.name).append(" / ").append(ship.name)
                        ship.size?.let { append("，尺寸 ").append(it) }
                        ship.crew?.let { append("，船员 ").append(it) }
                        ship.cargo?.let { append("，货仓 ").append(it) }
                        append("，武器/组件槽位 ").append(ship.slots.size).append(" 个")
                    },
                    facts = listOfNotNull(
                        AgentFact("船只", ship.zhName ?: ship.name),
                        ship.size?.let { AgentFact("尺寸", it) },
                        ship.crew?.let { AgentFact("船员", it) },
                        ship.cargo?.let { AgentFact("货仓", it) },
                        AgentFact("槽位数量", ship.slots.size.toString()),
                    ),
                    sources = listOf(AgentSource("shipfit assets", "local", ship.id)),
                    confidence = confidence(score),
                )
            }.toList()

    private fun searchMining(query: AgentQuery): List<AgentSearchHit> =
        miningMatches(query).take(3).map { element ->
            val occurrences = mining.findElementOccurrences(element.guid).take(3)
            AgentSearchHit(
                summary = buildString {
                    append(element.displayName).append(" / ").append(element.nameEn)
                    element.rarity?.let { append("，稀有度 ").append(it) }
                    if (occurrences.isNotEmpty()) {
                        append("，常见地点：")
                        append(occurrences.joinToString("；") { it.location.displayName })
                    }
                },
                facts = listOfNotNull(
                    AgentFact("矿物", element.displayName),
                    element.rarity?.let { AgentFact("稀有度", it) },
                    AgentFact("阻力", "%.2f".format(element.resistance)),
                    AgentFact("不稳定性", "%.2f".format(element.instability)),
                    occurrences.firstOrNull()?.let { AgentFact("推荐查询地点", it.location.displayName) },
                ),
                sources = listOf(AgentSource("mining assets", "local", element.guid)),
                confidence = 0.85f,
            )
        }

    private fun searchBlueprints(query: AgentQuery): List<AgentSearchHit> =
        blueprintMatches(query).asSequence()
            .map { it to matchScore(query, it.nameEn, it.category, *it.materials.toTypedArray()) }
            .filter { it.second > 0 || query.entities.any { entity -> entity.type == "blueprint" && entity.value == it.first.nameEn } }
            .sortedByDescending { it.second }
            .take(3)
            .map { (entry, score) ->
                val missions = blueprint.loadMissionsForBlueprint(entry.nameEn).take(3)
                AgentSearchHit(
                    summary = buildString {
                        append(entry.nameEn).append(" 蓝图")
                        if (entry.category.isNotBlank()) append("，分类 ").append(entry.category)
                        if (entry.materials.isNotEmpty()) append("，材料 ").append(entry.materials.take(5).joinToString(" / "))
                        if (missions.isNotEmpty()) append("，来源任务 ").append(missions.joinToString(" / ") { it.displayTitle })
                    },
                    facts = listOfNotNull(
                        AgentFact("蓝图", entry.nameEn),
                        entry.category.takeIf { it.isNotBlank() }?.let { AgentFact("分类", it) },
                        AgentFact("制作时间", "${entry.craftTimeSeconds}s"),
                        AgentFact("材料", entry.materials.take(6).joinToString(" / ")),
                        missions.firstOrNull()?.let { AgentFact("任务来源", it.displayTitle) },
                    ),
                    sources = listOf(AgentSource("blueprint assets", "local", entry.nameEn)),
                    confidence = confidence(score),
                )
            }.toList()

    private fun searchMissions(query: AgentQuery): List<AgentSearchHit> =
        blueprint.loadAllMissions().asSequence()
            .map { it to matchScore(query, it.displayTitle, it.title, it.titleCn, it.missionType, it.missionTypeCn, it.displayFaction, it.displaySystems) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { (mission, score) ->
                AgentSearchHit(
                    summary = buildString {
                        append(mission.displayTitle)
                        mission.displayFaction?.let { append("，阵营 ").append(it) }
                        append("，星系 ").append(mission.displaySystems)
                        mission.rewardUec?.let { append("，奖励 ").append(it).append(" aUEC") }
                        if (mission.blueprints.isNotEmpty()) append("，蓝图 ").append(mission.blueprints.joinToString(" / ") { it.nameEn })
                    },
                    facts = listOfNotNull(
                        AgentFact("任务", mission.displayTitle),
                        mission.displayFaction?.let { AgentFact("阵营", it) },
                        AgentFact("星系", mission.displaySystems),
                        mission.rewardUec?.let { AgentFact("奖励", "$it aUEC") },
                        mission.blueprints.firstOrNull()?.let { AgentFact("掉落蓝图", it.nameEn) },
                    ),
                    sources = listOf(AgentSource("mission assets", "local", mission.guid)),
                    confidence = confidence(score),
                )
            }.toList()

    private fun searchWikelo(query: AgentQuery): List<AgentSearchHit> =
        wikeloMatches(query).take(3).map { trade ->
            AgentSearchHit(
                summary = buildString {
                    append(trade.nameCn).append(" / ").append(trade.nameEn)
                    trade.rewardItem?.let { append("，奖励 ").append(it) }
                    if (trade.materials.isNotEmpty()) append("，材料 ").append(trade.materials.joinToString(" / ") { "${it.nameCn}${it.qty ?: ""}${it.unit ?: ""}" })
                },
                facts = listOfNotNull(
                    AgentFact("兑换", trade.nameCn),
                    trade.rewardItem?.let { AgentFact("奖励", it) },
                    AgentFact("材料", trade.materials.joinToString(" / ") { "${it.nameCn}${it.qty ?: ""}${it.unit ?: ""}" }),
                    trade.reputation.requiredTier?.let { AgentFact("声望等级", it.toString()) },
                ),
                sources = listOf(AgentSource("wikelo assets", "local", trade.nameEn)),
                confidence = 0.82f,
            )
        }

    private fun miningMatches(query: AgentQuery) =
        query.entities
            .filter { it.type == "mining_element" }
            .mapNotNull { mining.getElement(it.value) }
            .ifEmpty { mining.elements.values.filter { matchScore(query, it.nameEn, it.nameCn) > 0 } }
            .sortedBy { it.displayName }

    private fun blueprintMatches(query: AgentQuery) =
        query.entities
            .filter { it.type == "blueprint" }
            .mapNotNull { entity -> blueprint.loadScCraftIndex().firstOrNull { it.nameEn == entity.value } }
            .ifEmpty { blueprint.loadScCraftIndex() }

    private fun wikeloMatches(query: AgentQuery) =
        query.entities
            .filter { it.type == "wikelo_trade" }
            .mapNotNull { entity -> wikelo.allTrades().firstOrNull { it.nameEn == entity.value } }
            .ifEmpty { wikelo.search(query.normalizedText) }

    private fun matchScore(query: AgentQuery, vararg values: String?): Int {
        val tokens = query.normalizedText.split(' ').filter { it.length >= 2 }
        return values.filterNotNull().sumOf { value ->
            val hay = value.lowercase()
            var score = 0
            if (hay.contains(query.normalizedText) || query.normalizedText.contains(hay)) score += 10
            tokens.forEach { token -> if (hay.contains(token)) score += token.length }
            score
        }
    }

    private fun confidence(score: Int): Float = (0.5f + score.coerceAtMost(10) / 20f).coerceAtMost(0.95f)
}
