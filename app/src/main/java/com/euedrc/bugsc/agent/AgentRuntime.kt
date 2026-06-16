package com.euedrc.bugsc.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

sealed class AgentRuntimeEvent {
    data object Thinking : AgentRuntimeEvent()
    data class ModelJson(val content: String) : AgentRuntimeEvent()
    data class ToolCallStarted(val tool: String) : AgentRuntimeEvent()
    data class ToolCallFinished(val tool: String) : AgentRuntimeEvent()
    data class FinalAnswerReady(val answer: String) : AgentRuntimeEvent()
}

class AgentRuntime(
    private val analyzer: QueryAnalyzer,
    private val skillRegistry: AgentSkillRegistry? = null,
    private val promptBuilder: AgentPromptBuilder? = null,
    private val deepSeekClient: DeepSeekClient? = null,
    private val settingsProvider: (() -> AgentSettings)? = null,
    private val toolRegistry: AgentToolRegistry? = null,
    private val toolCallingEnabled: Boolean = false,
    private val maxToolCallingSteps: Int = DEFAULT_MAX_TOOL_CALLING_STEPS,
    private val observer: ((AgentRuntimeEvent) -> Unit)? = null,
) {
    suspend fun query(text: String): List<SkillResult> {
        val query = analyzer.analyze(text)
        skillRegistry?.let { return it.execute(query) }
        return emptyList()
    }

    suspend fun answer(text: String, history: List<AgentMessage> = emptyList()): String {
        if (toolCallingEnabled && toolRegistry != null) {
            return answerWithToolCallingLoop(text, history)
        }
        val query = analyzer.analyze(text)
        val results = skillRegistry?.execute(query).orEmpty()
        val fallback = fallbackAnswer(text, results)
        val builder = promptBuilder ?: return fallback
        val client = deepSeekClient ?: return fallback
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val answer = client.chat(settings, builder.build(text, history, results))
        return if (answer.looksLikePseudoToolCall()) fallback else answer
    }

    private suspend fun answerWithToolCallingLoop(
        text: String,
        history: List<AgentMessage>,
    ): String {
        val registry = toolRegistry ?: throw AgentToolCallParseException("tool registry unavailable")
        val builder = promptBuilder ?: throw AgentToolCallParseException("prompt builder unavailable")
        val client = deepSeekClient ?: throw AgentToolCallParseException("deepseek client unavailable")
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val executor = AgentToolExecutor(registry)
        val loopMessages = mutableListOf<DeepSeekMessage>()
        val toolResults = mutableListOf<AgentToolResult>()
        val seenCalls = LinkedHashSet<String>()
        repeat(maxToolCallingSteps) {
            observer?.invoke(AgentRuntimeEvent.Thinking)
            val content = client.chat(
                settings,
                builder.buildToolCalling(
                    userText = text,
                    history = history,
                    tools = registry.definitions(),
                    loopMessages = loopMessages,
                ),
            )
            runCatching { Log.d(TAG, "model_json=$content") }
            observer?.invoke(AgentRuntimeEvent.ModelJson(content))
            val action = AgentToolCallParser.parse(content).getOrElse {
                return groundedLoopFallback(text, toolResults)
            }
            when (action) {
                is AgentModelAction.FinalAnswer -> {
                    if (action.answer.looksLikePseudoToolCall()) {
                        return groundedLoopFallback(text, toolResults)
                    }
                    observer?.invoke(AgentRuntimeEvent.FinalAnswerReady(action.answer))
                    return action.answer
                }
                is AgentModelAction.ToolCall -> {
                    val call = AgentToolCall(
                        tool = action.tool,
                        args = action.arguments,
                        reason = "model_tool_call",
                    )
                    val callKey = call.stableKey()
                    if (!seenCalls.add(callKey)) {
                        return groundedLoopFallback(text, toolResults)
                    }
                    observer?.invoke(AgentRuntimeEvent.ToolCallStarted(call.tool))
                    val result = executor.execute(call)
                    if (result.error != null) {
                        return groundedLoopFallback(text, toolResults + result)
                    }
                    runCatching { Log.d(TAG, "tool_result=${formatToolResultMessage(result)}") }
                    toolResults += result
                    observer?.invoke(AgentRuntimeEvent.ToolCallFinished(call.tool))
                    loopMessages += DeepSeekMessage("assistant", content)
                    loopMessages += DeepSeekMessage("system", formatToolResultMessage(result))
                }
            }
        }
        return groundedLoopFallback(text, toolResults)
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

    private fun formatUsefulResults(
        summaries: List<String>,
        facts: List<AgentFact>,
    ): String {
        val valuesByLabel = facts
            .filter { it.value.isNotBlank() }
            .groupBy { it.label }
            .mapValues { (_, values) -> values.map { it.value }.distinct() }
        if (valuesByLabel.keys.any { it in setOf("订单", "单价", "卖家") }) {
            return summaries
                .filter(String::isNotBlank)
                .flatMap { it.lineSequence().map(String::trim).filter(String::isNotBlank).toList() }
                .distinct()
                .take(6)
                .joinToString("\n")
        }
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

    private fun groundedLoopFallback(text: String, results: List<AgentToolResult>): String {
        val useful = results.filter { it.hasUsefulData() }
        if (useful.isNotEmpty()) {
            return formatUsefulResults(
                summaries = useful.map { it.summary },
                facts = useful.flatMap { it.facts },
            ).ifBlank { useful.joinToString("\n") { it.summary }.trim() }
        }
        val summaries = results
            .filter { it.error == null }
            .map { it.summary.trim() }
            .filter(String::isNotBlank)
            .distinct()
        return if (summaries.isNotEmpty()) {
            summaries.take(3).joinToString("\n")
        } else {
            "没有查到“$text”的可靠资料。"
        }
    }

    private fun AgentToolResult.toSkillResult(): SkillResult = SkillResult(
        skillId = call.tool,
        summary = summary,
        facts = facts,
        sources = sources,
        confidence = confidence,
        error = error,
    )

    private fun formatToolResultMessage(result: AgentToolResult): String =
        JSONObject()
            .put("type", "tool_result")
            .put("tool", result.call.tool)
            .put("summary", result.summary)
            .put(
                "facts",
                JSONArray(
                    result.facts.map { fact ->
                        JSONObject()
                            .put("label", fact.label)
                            .put("value", fact.value)
                    },
                ),
            )
            .put(
                "sources",
                JSONArray(
                    result.sources.map { source ->
                        JSONObject()
                            .put("name", source.name)
                            .put("type", source.type)
                            .put("detail", source.detail)
                    },
                ),
            )
            .toString()

    private fun AgentToolCall.stableKey(): String =
        tool + "|" + args.toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" }

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

    companion object {
        private const val TAG = "MobiGuide"
        private const val DEFAULT_MAX_TOOL_CALLING_STEPS = 6
    }
}
