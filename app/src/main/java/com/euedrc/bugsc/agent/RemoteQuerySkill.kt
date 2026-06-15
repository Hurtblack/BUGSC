package com.euedrc.bugsc.agent

abstract class RemoteQuerySkill(
    override val id: String,
    override val name: String,
    private val client: RemoteQueryClient,
    private val isAuthenticated: () -> Boolean,
    private val matchingIntents: Set<AgentIntent>,
) : AgentSkill {

    override fun match(query: AgentQuery): SkillMatch {
        val score = query.intents
            .filter { it.intent in matchingIntents }
            .maxOfOrNull { it.score }
            ?: 0
        return SkillMatch(id, score, "remote query")
    }

    override suspend fun execute(query: AgentQuery): SkillResult {
        return runCatching {
            val hits = client.query(query, isAuthenticated())
            if (hits.isEmpty()) {
                SkillResult(
                    skillId = id,
                    summary = "$name 未命中远程数据",
                    facts = emptyList(),
                    sources = listOf(AgentSource(name, "remote")),
                    confidence = 0f,
                )
            } else {
                SkillResult(
                    skillId = id,
                    summary = hits.joinToString("\n") { it.summary },
                    facts = hits.flatMap { it.facts },
                    sources = hits.flatMap { it.sources }.distinct(),
                    confidence = hits.maxOf { it.confidence },
                )
            }
        }.getOrElse { error ->
            SkillResult(
                skillId = id,
                summary = "$name 暂不可用",
                facts = emptyList(),
                sources = listOf(AgentSource(name, "remote")),
                confidence = 0f,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }
}
