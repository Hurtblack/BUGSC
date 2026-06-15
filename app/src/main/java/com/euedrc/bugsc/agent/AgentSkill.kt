package com.euedrc.bugsc.agent

interface AgentSkill {
    val id: String
    val name: String

    fun match(query: AgentQuery): SkillMatch

    suspend fun execute(query: AgentQuery): SkillResult
}

data class PlannedSkill(
    val skill: AgentSkill,
    val match: SkillMatch,
)
