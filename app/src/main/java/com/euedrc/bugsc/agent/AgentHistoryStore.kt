package com.euedrc.bugsc.agent

import org.json.JSONArray
import org.json.JSONObject

class AgentHistoryStore(
    private val kv: AgentSettingsKvStore,
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
) {

    @Synchronized
    fun load(): List<AgentMessage> {
        val raw = kv.getString(KEY_HISTORY).orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.let(::parseMessage)
        }
    }

    @Synchronized
    fun save(messages: List<AgentMessage>) {
        val arr = JSONArray()
        trim(messages).forEach { msg ->
            arr.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("role", msg.role.name)
                    .put("content", msg.content)
                    .put("createdAt", msg.createdAt)
                    .put("status", msg.status.name)
                    .put("toolSummary", msg.toolSummary),
            )
        }
        kv.putString(KEY_HISTORY, arr.toString())
    }

    @Synchronized
    fun append(message: AgentMessage) {
        save(load() + message)
    }

    @Synchronized
    fun clear() {
        kv.remove(KEY_HISTORY)
    }

    private fun trim(messages: List<AgentMessage>): List<AgentMessage> {
        if (maxMessages <= 0) return emptyList()
        return messages.takeLast(maxMessages)
    }

    private fun parseMessage(obj: JSONObject): AgentMessage? {
        val role = runCatching { AgentMessageRole.valueOf(obj.optString("role")) }.getOrNull() ?: return null
        val status = runCatching { AgentMessageStatus.valueOf(obj.optString("status")) }.getOrNull()
            ?: AgentMessageStatus.SENT
        return AgentMessage(
            id = obj.optString("id"),
            role = role,
            content = obj.optString("content"),
            createdAt = obj.optLong("createdAt"),
            status = status,
            toolSummary = obj.optString("toolSummary"),
        )
    }

    companion object {
        private const val KEY_HISTORY = "agentHistory"
        private const val DEFAULT_MAX_MESSAGES = 120
    }
}
