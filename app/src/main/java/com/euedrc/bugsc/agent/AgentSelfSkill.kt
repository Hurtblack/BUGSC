package com.euedrc.bugsc.agent

class AgentSelfSkill(private val profile: AgentProfile) : AgentSkill {
    override val id: String = "agent_self"
    override val name: String = "Agent 人物卡"

    override fun match(query: AgentQuery): SkillMatch {
        val hasSelfHelp = query.intents.any { it.intent == AgentIntent.SELF_HELP }
        return SkillMatch(id, if (hasSelfHelp) MATCH_SCORE else 0, "self help")
    }

    override suspend fun execute(query: AgentQuery): SkillResult {
        val summary = buildString {
            append("我是 ${profile.displayName}，${profile.tagline}。")
            append(profile.roleDescription)
            append(" ")
            append(profile.privacyNotes.joinToString("；"))
        }
        return SkillResult(
            skillId = id,
            summary = summary,
            facts = listOf(
                AgentFact("显示名", profile.displayName),
                AgentFact("代号", profile.codename),
                AgentFact("定位", profile.tagline),
                AgentFact("身份", profile.roleDescription),
                AgentFact("隐私", profile.privacyNotes.joinToString("；")),
            ),
            sources = listOf(AgentSource("本地 Agent 人物卡", "local")),
            confidence = 1f,
        )
    }

    companion object {
        const val MATCH_SCORE = 100
    }
}
