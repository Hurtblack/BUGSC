package com.euedrc.bugsc.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

data class AgentToolCall(
    val tool: String,
    val args: Map<String, String>,
    val reason: String = "",
)

data class AgentToolResult(
    val call: AgentToolCall,
    val summary: String,
    val facts: List<AgentFact>,
    val sources: List<AgentSource>,
    val confidence: Float,
    val error: String? = null,
)

data class AgentToolParameter(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val type: String = "string",
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<AgentToolParameter>,
) {
    fun toJson(): JSONObject {
        val requiredNames = parameters.filter { it.required }.map { it.name }
        val properties = JSONObject()
        parameters.forEach { parameter ->
            properties.put(
                parameter.name,
                JSONObject()
                    .put("type", parameter.type)
                    .put("description", parameter.description),
            )
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(requiredNames)),
            )
    }
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: List<AgentToolParameter>
        get() = listOf(AgentToolParameter("query", "用户要查询的关键词、物品名、船名、任务名或地点名"))

    suspend fun run(call: AgentToolCall): AgentToolResult
}

class AgentToolRegistry(tools: List<AgentTool>) {
    private val toolMap = tools.associateBy { it.name }

    fun find(name: String): AgentTool? = toolMap[name]

    fun definitions(): List<AgentToolDefinition> =
        toolMap.values.map { tool -> AgentToolDefinition(tool.name, tool.description, tool.parameters) }

    suspend fun execute(calls: List<AgentToolCall>): List<AgentToolResult> =
        AgentToolExecutor(this).execute(calls)
}

class AgentToolExecutor(private val registry: AgentToolRegistry) {

    suspend fun execute(calls: List<AgentToolCall>): List<AgentToolResult> = coroutineScope {
        calls.map { call ->
            async {
                execute(call)
            }
        }.awaitAll()
    }

    suspend fun execute(call: AgentToolCall): AgentToolResult {
        val tool = registry.find(call.tool)
            ?: return AgentToolResult(
                call = call,
                summary = "${call.tool} 暂不可用",
                facts = emptyList(),
                sources = listOf(AgentSource(call.tool, "tool")),
                confidence = 0f,
                error = "tool not registered",
            )
        val normalized = normalizeArgs(call)
        val missing = tool.parameters
            .filter { it.required }
            .map { it.name }
            .filter { normalized.args[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return AgentToolResult(
                call = normalized,
                summary = "${tool.description} 参数不完整",
                facts = emptyList(),
                sources = listOf(AgentSource(tool.description, "tool")),
                confidence = 0f,
                error = "missing required arguments: ${missing.joinToString(", ")}",
            )
        }
        return runCatching { tool.run(normalized) }
            .getOrElse { error ->
                AgentToolResult(
                    call = normalized,
                    summary = "${tool.description} 暂不可用",
                    facts = emptyList(),
                    sources = listOf(AgentSource(tool.description, "tool")),
                    confidence = 0f,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
    }

    private fun normalizeArgs(call: AgentToolCall): AgentToolCall {
        val args = call.args.toMutableMap()
        val query = args["query"].orEmpty().ifBlank { args["term"].orEmpty() }.trim()
        if (query.isNotBlank()) {
            args["query"] = query
            args["term"] = query
        }
        return call.copy(args = args)
    }
}
