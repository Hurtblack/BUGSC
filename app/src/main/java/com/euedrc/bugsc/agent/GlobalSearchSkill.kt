package com.euedrc.bugsc.agent

class GlobalSearchSkill(private val entityIndex: AgentEntityIndex) : AgentSkill {
    override val id: String = "global_search"
    override val name: String = "本地全局搜索"

    override fun match(query: AgentQuery): SkillMatch {
        val score = if (query.intents.any { it.intent == AgentIntent.UNKNOWN } || query.entities.isEmpty()) 1 else 0
        return SkillMatch(id, score, "fallback search")
    }

    override suspend fun execute(query: AgentQuery): SkillResult {
        val hits = entityIndex.entries.filter { entity ->
            entity.aliases.any { alias -> alias.isNotBlank() && query.normalizedText.contains(alias.lowercase()) }
        }.take(MAX_RESULTS)
        return SkillResult(
            skillId = id,
            summary = if (hits.isEmpty()) "未命中本地索引" else hits.joinToString("；") { it.displayName },
            facts = hits.map { AgentFact(it.type, it.displayName) },
            sources = listOf(AgentSource("App 本地索引", "local")),
            confidence = if (hits.isEmpty()) 0f else 0.4f,
        )
    }

    companion object {
        const val MAX_RESULTS = 5
    }
}
