package com.euedrc.bugsc.agent

import org.json.JSONObject

sealed class AgentModelAction {
    data class ToolCall(
        val tool: String,
        val arguments: Map<String, String>,
    ) : AgentModelAction()

    data class FinalAnswer(
        val answer: String,
    ) : AgentModelAction()
}

class AgentToolCallParseException(message: String) : IllegalArgumentException(message)

object AgentToolCallParser {
    fun parse(content: String): Result<AgentModelAction> = runCatching {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw AgentToolCallParseException("模型输出不是单个 JSON 对象")
        }
        val json = runCatching { JSONObject(trimmed) }
            .getOrElse { throw AgentToolCallParseException("模型输出不是合法 JSON") }
        when (val type = json.optString("type")) {
            "tool_call" -> parseToolCall(json)
            "final_answer" -> parseFinalAnswer(json)
            else -> throw AgentToolCallParseException("未知输出类型：$type")
        }
    }

    private fun parseToolCall(json: JSONObject): AgentModelAction.ToolCall {
        requireExactKeys(json, setOf("type", "tool", "arguments"))
        val tool = json.optString("tool").trim()
        if (tool.isBlank()) throw AgentToolCallParseException("tool 不能为空")
        val argsJson = json.optJSONObject("arguments")
            ?: throw AgentToolCallParseException("arguments 必须是 JSON 对象")
        val arguments = argsJson.keys().asSequence().associateWith { key ->
            val value = argsJson.opt(key)
            if (value == null || value == JSONObject.NULL) {
                throw AgentToolCallParseException("参数 $key 不能为空")
            }
            value.toString().trim()
        }
        return AgentModelAction.ToolCall(tool, arguments)
    }

    private fun parseFinalAnswer(json: JSONObject): AgentModelAction.FinalAnswer {
        requireExactKeys(json, setOf("type", "answer"))
        val answer = json.optString("answer").trim()
        if (answer.isBlank()) throw AgentToolCallParseException("answer 不能为空")
        return AgentModelAction.FinalAnswer(answer)
    }

    private fun requireExactKeys(json: JSONObject, expected: Set<String>) {
        val actual = json.keys().asSequence().toSet()
        if (actual != expected) {
            throw AgentToolCallParseException("JSON 字段必须是：${expected.joinToString(", ")}")
        }
    }
}
