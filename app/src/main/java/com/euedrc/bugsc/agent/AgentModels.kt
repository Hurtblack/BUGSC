package com.euedrc.bugsc.agent

data class AgentProfile(
    val displayName: String,
    val codename: String,
    val tagline: String,
    val roleDescription: String,
    val capabilities: List<String>,
    val dataSources: List<String>,
    val privacyNotes: List<String>,
    val limitations: List<String>,
)

enum class AgentIntent {
    SHIP_INFO,
    MINING,
    BLUEPRINT,
    MISSION,
    MARKET,
    WIKELO,
    SELF_HELP,
    GUIDE,
    UNKNOWN,
}

data class ScoredIntent(
    val intent: AgentIntent,
    val score: Int,
)

data class ScoredEntity(
    val type: String,
    val value: String,
    val displayName: String,
    val score: Int,
)

data class AgentEntity(
    val type: String,
    val value: String,
    val displayName: String,
    val aliases: List<String>,
)

data class AgentEntityIndex(
    val entries: List<AgentEntity> = emptyList(),
)

data class AgentQuery(
    val rawText: String,
    val normalizedText: String,
    val intents: List<ScoredIntent>,
    val entities: List<ScoredEntity>,
)

data class SkillMatch(
    val skillId: String,
    val score: Int,
    val reason: String,
)

data class AgentFact(
    val label: String,
    val value: String,
)

data class AgentSource(
    val name: String,
    val type: String,
    val detail: String = "",
)

data class SkillResult(
    val skillId: String,
    val summary: String,
    val facts: List<AgentFact>,
    val sources: List<AgentSource>,
    val confidence: Float,
    val error: String? = null,
)

enum class AgentMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class AgentMessageStatus {
    SENDING,
    SENT,
    FAILED,
}

data class AgentMessage(
    val id: String,
    val role: AgentMessageRole,
    val content: String,
    val createdAt: Long,
    val status: AgentMessageStatus,
    val toolSummary: String = "",
)
