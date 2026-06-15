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
            return formatUsefulResults(
                summaries = useful.map { it.summary },
                facts = useful.flatMap { it.facts },
            )
        }
        return "没有查到“$text”的可靠资料。\n可以换中文名、英文名或更完整的物品名再试。"
    }

    private fun fallbackToolAnswer(text: String, results: List<AgentToolResult>): String {
        val useful = results.filter { it.hasUsefulData() }
        if (useful.isNotEmpty()) {
            return formatUsefulResults(
                summaries = useful.map { it.summary },
                facts = useful.flatMap { it.facts },
            )
        }
        return "没有查到“$text”的可靠资料。\n可以换中文名、英文名或更完整的物品名再试。"
    }

    private fun formatUsefulResults(
        summaries: List<String>,
        facts: List<AgentFact>,
    ): String {
        val valuesByLabel = facts
            .filter { it.value.isNotBlank() }
            .groupBy { it.label }
            .mapValues { (_, values) -> values.map { it.value }.distinct() }
        val blueprints = valuesByLabel["蓝图"].orEmpty().take(3)
        if (blueprints.isNotEmpty()) {
            return buildString {
                appendSection("蓝图", blueprints)
                appendSection("材料", valuesByLabel["材料"].orEmpty().take(3))
                appendSection("来源", (valuesByLabel["任务来源"].orEmpty() + valuesByLabel["兑换"].orEmpty()).distinct().take(3))
                val firstSummary = summaries.firstOrNull { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.trim()
                if (!firstSummary.isNullOrBlank()) appendSection("结论", listOf(firstSummary))
            }.trim()
        }
        val preferred = listOf("矿物", "船只", "任务", "兑换", "奖励", "地点", "推荐查询地点")
            .flatMap { label -> valuesByLabel[label].orEmpty().take(2).map { label to it } }
            .take(6)
        if (preferred.isNotEmpty()) {
            val headline = summaries
                .firstOrNull { it.isNotBlank() && !it.contains("未命中") }
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
            return (listOfNotNull(headline) + preferred.map { (label, value) -> "- $label：$value" })
                .distinct()
                .take(6)
                .joinToString("\n")
        }
        return summaries
            .filter { it.isNotBlank() && !it.contains("未命中") }
            .flatMap { it.lineSequence().map(String::trim).filter(String::isNotBlank).toList() }
            .distinct()
            .take(6)
            .joinToString("\n")
    }

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        val visible = lines.filter { it.isNotBlank() }.distinct()
        if (visible.isEmpty()) return
        if (isNotEmpty()) appendLine().appendLine()
        appendLine("### $title")
        visible.forEach { appendLine("- $it") }
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
