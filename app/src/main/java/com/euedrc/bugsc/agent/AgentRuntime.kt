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
        val fallback = fallbackAnswer(text, results)
        val builder = promptBuilder ?: return fallback
        val client = deepSeekClient ?: return fallback
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val answer = client.chat(settings, builder.build(text, history, results))
        return if (answer.looksLikePseudoToolCall()) fallback else answer
    }

    private fun fallbackAnswer(text: String, results: List<SkillResult>): String {
        val useful = results.filter { it.hasUsefulData() }
        if (useful.isNotEmpty()) {
            return useful.joinToString("\n\n") { result ->
                buildString {
                    append(result.summary)
                    val facts = result.facts.filter { it.value.isNotBlank() }
                    if (facts.isNotEmpty()) {
                        appendLine()
                        facts.take(8).forEach { appendLine("- ${it.label}: ${it.value}") }
                    }
                    if (result.sources.isNotEmpty()) {
                        append("来源：")
                        append(result.sources.joinToString { it.name })
                    }
                }.trim()
            }
        }
        val tried = results.map { it.skillId }.distinct().joinToString("、").ifBlank { "本地索引" }
        return "我在本地资料没有命中“$text”的可靠结果。\n\n已尝试：$tried。\n你可以换一个游戏内英文名、物品完整名称，或补充它属于矿物、蓝图材料、任务奖励还是维科洛兑换；我会按对应资料库再查。"
    }

    private fun SkillResult.hasUsefulData(): Boolean =
        error == null && confidence > 0f && (facts.isNotEmpty() || !summary.contains("未命中"))

    private fun String.looksLikePseudoToolCall(): Boolean {
        val value = trim().lowercase()
        return value.contains("<search") ||
            value.contains("</search>") ||
            value.contains("<tool") ||
            value.contains("function_call") ||
            value.contains("\"tool\"") ||
            value.contains("我再查") ||
            value.contains("正在查询")
    }
}
