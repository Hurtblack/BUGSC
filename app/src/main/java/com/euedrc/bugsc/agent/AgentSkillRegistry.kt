package com.euedrc.bugsc.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AgentSkillRegistry(
    private val skills: List<AgentSkill>,
    private val maxSkills: Int = DEFAULT_MAX_SKILLS,
) {

    fun plan(query: AgentQuery): List<PlannedSkill> =
        skills.map { skill -> PlannedSkill(skill, skill.match(query)) }
            .filter { it.match.score > 0 }
            .sortedByDescending { it.match.score }
            .take(maxSkills)

    suspend fun execute(query: AgentQuery): List<SkillResult> = coroutineScope {
        plan(query).map { planned ->
            async {
                runCatching { planned.skill.execute(query) }
                    .getOrElse { error ->
                        SkillResult(
                            skillId = planned.skill.id,
                            summary = "",
                            facts = emptyList(),
                            sources = listOf(AgentSource(planned.skill.name, "skill")),
                            confidence = 0f,
                            error = error.message ?: error::class.java.simpleName,
                        )
                    }
            }
        }.awaitAll()
    }

    companion object {
        const val DEFAULT_MAX_SKILLS = 5
    }
}
