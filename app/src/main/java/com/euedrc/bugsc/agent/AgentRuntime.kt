package com.euedrc.bugsc.agent

class AgentRuntime(
    private val analyzer: QueryAnalyzer,
    private val skillRegistry: AgentSkillRegistry,
    private val promptBuilder: AgentPromptBuilder? = null,
    private val deepSeekClient: DeepSeekClient? = null,
    private val settingsProvider: (() -> AgentSettings)? = null,
) {
    suspend fun query(text: String): List<SkillResult> {
        val query = analyzer.analyze(text)
        return skillRegistry.execute(query)
    }

    suspend fun answer(text: String, history: List<AgentMessage> = emptyList()): String {
        val query = analyzer.analyze(text)
        val results = skillRegistry.execute(query)
        val builder = promptBuilder ?: return results.joinToString("\n") { it.summary }
        val client = deepSeekClient ?: return results.joinToString("\n") { it.summary }
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        return client.chat(settings, builder.build(text, history, results))
    }
}
