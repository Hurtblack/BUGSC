package com.euedrc.bugsc.agent

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

sealed class AgentRuntimeEvent {
    data object Thinking : AgentRuntimeEvent()
    data class ModelJson(val content: String) : AgentRuntimeEvent()
    data class AnswerDelta(val delta: String) : AgentRuntimeEvent()
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
    /** 本轮工具调用积累的可复用证据，供调用方写入历史，让后续追问能引用结构化结果。 */
    var lastToolEvidence: String = ""
        private set

    suspend fun query(text: String): List<SkillResult> {
        val query = analyzer.analyze(text)
        skillRegistry?.let { return it.execute(query) }
        return emptyList()
    }

    suspend fun answer(text: String, history: List<AgentMessage> = emptyList()): String {
        return try {
            withTimeout(DEFAULT_ROUND_DEADLINE_MS) {
                answerWithinDeadline(text, history)
            }
        } catch (error: TimeoutCancellationException) {
            throw DeepSeekClientException("Agent 本轮请求超时，请稍后重试", error)
        }
    }

    private suspend fun answerWithinDeadline(text: String, history: List<AgentMessage> = emptyList()): String {
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
        val registry = toolRegistry ?: error("tool registry unavailable")
        val builder = promptBuilder ?: error("prompt builder unavailable")
        val client = deepSeekClient ?: error("deepseek client unavailable")
        val settings = settingsProvider?.invoke() ?: throw DeepSeekClientException("请先配置 DeepSeek")
        val executor = AgentToolExecutor(registry)
        val tools = registry.definitions()
        val loopMessages = mutableListOf<DeepSeekMessage>()
        val toolResults = mutableListOf<AgentToolResult>()
        val seenCalls = LinkedHashSet<String>()
        repeat(maxToolCallingSteps) {
            observer?.invoke(AgentRuntimeEvent.Thinking)
            val response = client.chatWithToolsStreaming(
                settings,
                builder.buildToolCalling(
                    userText = text,
                    history = history,
                    loopMessages = loopMessages,
                ),
                tools,
                onDelta = { delta -> observer?.invoke(AgentRuntimeEvent.AnswerDelta(delta)) },
            )
            runCatching { Log.d(TAG, "model_response=${formatModelResponse(response)}") }
            observer?.invoke(AgentRuntimeEvent.ModelJson(formatModelResponse(response)))
            // 没有工具调用即视为最终回答；伪查询/JSON 残留交给 asPlainNaturalAnswer 过滤。
            if (response.toolCalls.isEmpty()) {
                response.content.asPlainNaturalAnswer()?.let { answer ->
                    observer?.invoke(AgentRuntimeEvent.FinalAnswerReady(answer))
                    return answer
                }
                if (toolResults.isEmpty()) {
                    deterministicToolFallback(text, history, registry, toolResults)?.let { return it }
                }
                return groundedLoopFallback(text, toolResults)
            }
            // 记录本轮 assistant 的 tool_calls，后续 tool 结果必须按 tool_call_id 对应回去。
            loopMessages += DeepSeekMessage("assistant", response.content, toolCalls = response.toolCalls)
            for (toolCall in response.toolCalls) {
                val call = AgentToolCall(
                    tool = toolCall.name,
                    args = parseArguments(toolCall.arguments),
                    reason = "model_tool_call",
                )
                if (!seenCalls.add(call.stableKey())) {
                    return deterministicToolFallback(text, history, registry, toolResults)
                        ?: groundedLoopFallback(text, toolResults)
                }
                observer?.invoke(AgentRuntimeEvent.ToolCallStarted(call.tool))
                val result = executor.execute(call)
                observer?.invoke(AgentRuntimeEvent.ToolCallFinished(call.tool))
                runCatching { Log.d(TAG, "tool_result=${formatToolResultMessage(result)}") }
                // 工具出错也要把错误回灌给模型，让它纠正参数或换工具，而不是直接退出循环。
                // 重复调用由 seenCalls 拦截、整体轮数由 maxToolCallingSteps 兜底。
                loopMessages += DeepSeekMessage("tool", formatToolResultMessage(result), toolCallId = toolCall.id)
                if (result.error == null) {
                    toolResults += result
                    lastToolEvidence = buildToolEvidence(toolResults)
                }
            }
        }
        return groundedLoopFallback(text, toolResults)
    }

    private fun parseArguments(raw: String): Map<String, String> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { key ->
            val value = json.opt(key)
            if (value == null || value == JSONObject.NULL) null else key to value.toString().trim()
        }.toMap()
    }

    private fun formatModelResponse(response: DeepSeekChatResult): String =
        if (response.toolCalls.isEmpty()) {
            response.content
        } else {
            response.toolCalls.joinToString(";") { "tool_call:${it.name}(${it.arguments})" }
        }

    private fun fallbackAnswer(text: String, results: List<SkillResult>): String {
        val useful = results.filter { AgentResultFormatter.hasUsefulData(it) }
        if (useful.isNotEmpty()) {
            return AgentResultFormatter.formatUsefulResults(
                summaries = useful.map { it.summary },
                facts = useful.flatMap { it.facts },
            )
        }
        return "没有查到“$text”的可靠资料。\n可以换中文名、英文名或更完整的物品名再试。"
    }

    private fun groundedLoopFallback(text: String, results: List<AgentToolResult>): String {
        val useful = results.filter { AgentResultFormatter.hasUsefulData(it) }
        if (useful.isNotEmpty()) {
            return AgentResultFormatter.formatUsefulResults(
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

    private suspend fun deterministicToolFallback(
        text: String,
        history: List<AgentMessage>,
        registry: AgentToolRegistry,
        existingResults: List<AgentToolResult>,
    ): String? {
        if (existingResults.isNotEmpty()) return null
        val call = deterministicToolCall(text) ?: return null
        val executor = AgentToolExecutor(registry)
        observer?.invoke(AgentRuntimeEvent.ToolCallStarted(call.tool))
        val result = executor.execute(call)
        observer?.invoke(AgentRuntimeEvent.ToolCallFinished(call.tool))
        if (result.error != null) return null
        lastToolEvidence = buildToolEvidence(listOf(result))
        return polishToolResults(
            text = text,
            history = history,
            results = listOf(result),
            fallback = groundedLoopFallback(text, listOf(result)),
        )
    }

    private fun polishToolResults(
        text: String,
        history: List<AgentMessage>,
        results: List<AgentToolResult>,
        fallback: String,
    ): String {
        val builder = promptBuilder ?: return fallback
        val client = deepSeekClient ?: return fallback
        val settings = runCatching { settingsProvider?.invoke() }.getOrNull() ?: return fallback
        val answer = runCatching {
            client.chat(
                settings,
                builder.build(
                    userText = text,
                    history = history,
                    skillResults = emptyList(),
                    toolResults = results,
                ),
            )
        }.getOrNull()?.trim().orEmpty()
        if (answer.isBlank() || answer.looksLikePseudoToolCall()) return fallback
        observer?.invoke(AgentRuntimeEvent.FinalAnswerReady(answer))
        return answer
    }

    private fun AgentToolResult.toSkillResult(): SkillResult = SkillResult(
        skillId = call.tool,
        summary = summary,
        facts = facts,
        sources = sources,
        confidence = confidence,
        error = error,
    )

    private fun buildToolEvidence(results: List<AgentToolResult>): String {
        val useful = results.filter { AgentResultFormatter.hasUsefulData(it) }
        if (useful.isEmpty()) return ""
        return useful.joinToString("\n") { result ->
            val facts = result.facts
                .filter { it.value.isNotBlank() }
                .take(6)
                .joinToString("；") { "${it.label}:${it.value}" }
            buildString {
                append(result.call.tool)
                val headline = result.summary.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (!headline.isNullOrBlank()) append(" 摘要:").append(headline)
                if (facts.isNotEmpty()) append(" | ").append(facts)
            }
        }.take(MAX_TOOL_EVIDENCE_CHARS)
    }

    private fun formatToolResultMessage(result: AgentToolResult): String =
        JSONObject()
            .put("type", "tool_result")
            .put("tool", result.call.tool)
            .apply { result.error?.let { put("error", it) } }
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

    private fun deterministicToolCall(text: String): AgentToolCall? =
        AgentToolIntents.deterministicCall(text)

    private fun String.looksLikePseudoToolCall(): Boolean {
        val value = trim().lowercase()
        return value.contains("<search") ||
            value.contains("</search>") ||
            value.contains("<tool") ||
            value.contains("function_call") ||
            value.contains("\"tool\"") ||
            value.contains("我再查") ||
            value.contains("我去查") ||
            value.contains("帮你查") ||
            value.contains("稍等我查") ||
            value.contains("正在查询")
    }

    private fun String.asPlainNaturalAnswer(): String? {
        val value = trim()
        if (value.isBlank() || value.startsWith("{") || value.endsWith("}")) return null
        if (value.looksLikePseudoToolCall()) return null
        return value
    }

    companion object {
        private const val TAG = "MobiGuide"
        private const val DEFAULT_MAX_TOOL_CALLING_STEPS = 6
        private const val DEFAULT_ROUND_DEADLINE_MS = 90_000L
        private const val MAX_TOOL_EVIDENCE_CHARS = 800
    }
}
