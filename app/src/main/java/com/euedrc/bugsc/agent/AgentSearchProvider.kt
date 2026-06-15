package com.euedrc.bugsc.agent

data class AgentSearchHit(
    val summary: String,
    val facts: List<AgentFact>,
    val sources: List<AgentSource>,
    val confidence: Float,
)

interface AgentSearchProvider {
    suspend fun search(query: AgentQuery): List<AgentSearchHit>
}

interface RemoteQueryClient {
    suspend fun query(query: AgentQuery, allowAuthenticated: Boolean): List<AgentSearchHit>
}
