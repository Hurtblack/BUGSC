package com.euedrc.bugsc.agent

class AgentRuntime(
    private val analyzer: QueryAnalyzer,
    private val skillRegistry: AgentSkillRegistry? = null,
    private val promptBuilder: AgentPromptBuilder? = null,
    private val deepSeekClient: DeepSeekClient? = null,
    private val settingsProvider: (() -> AgentSettings)? = null,
    private val planner: AgentPlanner? = null,
    private val toolRegistry: AgentToolRegistry? = null,
) {
    suspend fun query(text: String): List<SkillResult> {
        val query = analyzer.analyze(text)
        skillRegistry?.let { return it.execute(query) }
        val plan = planner?.plan(query) ?: return emptyList()
        return toolRegistry?.execute(plan.toolCalls)?.map { it.toSkillResult() }.orEmpty()
    }

    suspend fun answer(text: String, history: List<AgentMessage> = emptyList()): String {
        val query = analyzer.analyze(text)
        if (planner != null && toolRegistry != null) {
            return answerWithTools(text, history, query)
        }
        val results = skillRegistry?.execute(query).orEmpty()
        val fallback = fallbackAnswer(text, results)
        val builder = promptBuilder ?: return fallback
        val client = deepSeekClient ?: return fallback
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val answer = client.chat(settings, builder.build(text, history, results))
        return if (answer.looksLikePseudoToolCall()) fallback else answer
    }

    private suspend fun answerWithTools(
        text: String,
        history: List<AgentMessage>,
        query: AgentQuery,
    ): String {
        val plan = planner?.plan(query) ?: AgentPlan(emptyList(), emptyList())
        val toolResults = toolRegistry?.execute(plan.toolCalls).orEmpty()
        val fallback = fallbackToolAnswer(text, toolResults)
        val builder = promptBuilder ?: return fallback
        val client = deepSeekClient ?: return fallback
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val answer = client.chat(
            settings,
            builder.build(
                userText = text,
                history = history,
                skillResults = emptyList(),
                skillCards = plan.skillCards,
                toolResults = toolResults,
            ),
        )
        return if (answer.looksLikePseudoToolCall() || answer.contradictsUsefulToolResults(toolResults)) fallback else answer
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

    private fun fallbackToolAnswer(text: String, results: List<AgentToolResult>): String {
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
        val tried = results.map { it.call.tool }.distinct().joinToString("、").ifBlank { "本地工具" }
        return "我在本地资料没有命中“$text”的可靠结果。\n\n已尝试：$tried。\n你可以补充它属于矿物、蓝图材料、任务奖励、维科洛兑换还是飞船资料；我会按对应工具再查。"
    }

    private fun SkillResult.hasUsefulData(): Boolean =
        error == null && confidence > 0f && (facts.isNotEmpty() || !summary.contains("未命中"))

    private fun AgentToolResult.hasUsefulData(): Boolean =
        error == null && confidence > 0f && (facts.isNotEmpty() || !summary.contains("未命中"))

    private fun AgentToolResult.toSkillResult(): SkillResult = SkillResult(
        skillId = call.tool,
        summary = summary,
        facts = facts,
        sources = sources,
        confidence = confidence,
        error = error,
    )

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

    private fun String.contradictsUsefulToolResults(results: List<AgentToolResult>): Boolean {
        if (results.none { it.hasUsefulData() }) return false
        return NO_RESULT_PHRASES.any { contains(it, ignoreCase = true) }
    }

    companion object {
        private val NO_RESULT_PHRASES = listOf(
            "不存在",
            "没有直接名为",
            "没有直接叫",
            "没有命中",
            "未命中",
            "查不到",
            "找不到",
            "没有找到",
            "没有一个官方名称",
            "不是游戏内置的正式译名",
        )
    }
}
