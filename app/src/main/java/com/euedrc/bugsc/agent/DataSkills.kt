package com.euedrc.bugsc.agent

abstract class SearchBackedSkill(
    override val id: String,
    override val name: String,
    private val provider: AgentSearchProvider,
    private val matchingIntents: Set<AgentIntent>,
) : AgentSkill {

    override fun match(query: AgentQuery): SkillMatch {
        val score = query.intents
            .filter { it.intent in matchingIntents }
            .maxOfOrNull { it.score }
            ?: 0
        return SkillMatch(id, score, "intent")
    }

    override suspend fun execute(query: AgentQuery): SkillResult {
        val hits = provider.search(query)
        if (hits.isEmpty()) {
            return SkillResult(
                skillId = id,
                summary = "$name 未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource(name, "local")),
                confidence = 0f,
            )
        }
        return SkillResult(
            skillId = id,
            summary = hits.joinToString("\n") { it.summary },
            facts = hits.flatMap { it.facts },
            sources = hits.flatMap { it.sources }.distinct(),
            confidence = hits.maxOf { it.confidence },
        )
    }
}

class ShipSkill(provider: AgentSearchProvider) : SearchBackedSkill(
    id = "ship",
    name = "飞船资料",
    provider = provider,
    matchingIntents = setOf(AgentIntent.SHIP_INFO),
)

class MiningSkill(provider: AgentSearchProvider) : SearchBackedSkill(
    id = "mining",
    name = "矿物资料",
    provider = provider,
    matchingIntents = setOf(AgentIntent.MINING),
)

class BlueprintSkill(provider: AgentSearchProvider) : SearchBackedSkill(
    id = "blueprint",
    name = "蓝图资料",
    provider = provider,
    matchingIntents = setOf(AgentIntent.BLUEPRINT),
)

class MissionSkill(provider: AgentSearchProvider) : SearchBackedSkill(
    id = "mission",
    name = "任务资料",
    provider = provider,
    matchingIntents = setOf(AgentIntent.MISSION),
)

class WikeloSkill(provider: AgentSearchProvider) : SearchBackedSkill(
    id = "wikelo",
    name = "维科洛兑换",
    provider = provider,
    matchingIntents = setOf(AgentIntent.WIKELO),
)
