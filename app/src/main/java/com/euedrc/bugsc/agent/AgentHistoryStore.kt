package com.euedrc.bugsc.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentConversationSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<AgentMessage>,
)

class AgentHistoryStore(
    private val kv: AgentSettingsKvStore,
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    private val maxArchivedSessions: Int = DEFAULT_MAX_ARCHIVED_SESSIONS,
    private val maxArchivedMessages: Int = DEFAULT_MAX_ARCHIVED_MESSAGES,
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
        kv.remove(KEY_ACTIVE_ARCHIVED_SESSION)
    }

    @Synchronized
    fun loadArchivedSessions(): List<AgentConversationSession> {
        val raw = kv.getString(KEY_ARCHIVED_SESSIONS).orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.let(::parseSession)
        }
    }

    @Synchronized
    fun archiveCurrentConversation(now: Long = System.currentTimeMillis()): Boolean {
        val messages = load()
        if (messages.none { it.role == AgentMessageRole.USER }) return false
        val archivedMessages = if (maxArchivedMessages <= 0) emptyList() else messages.takeLast(maxArchivedMessages)
        if (archivedMessages.isEmpty()) return false
        val activeArchivedSessionId = kv.getString(KEY_ACTIVE_ARCHIVED_SESSION).orEmpty().ifBlank { null }
        val previousSessions = loadArchivedSessions()
        val previousSession = previousSessions.firstOrNull { it.id == activeArchivedSessionId }
        val title = messages.firstOrNull { it.role == AgentMessageRole.USER }
            ?.content
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(MAX_TITLE_CHARS)
            ?.ifBlank { null }
            ?: "未命名对话"
        val session = AgentConversationSession(
            id = previousSession?.id ?: UUID.randomUUID().toString(),
            title = title,
            createdAt = previousSession?.createdAt ?: messages.firstOrNull()?.createdAt ?: now,
            updatedAt = now,
            messages = archivedMessages,
        )
        saveArchivedSessions(
            (listOf(session) + previousSessions.filterNot { it.id == session.id })
                .take(maxArchivedSessions.coerceAtLeast(0)),
        )
        kv.remove(KEY_ACTIVE_ARCHIVED_SESSION)
        return true
    }

    @Synchronized
    fun restoreArchivedSession(sessionId: String): Boolean {
        val session = loadArchivedSessions().firstOrNull { it.id == sessionId } ?: return false
        save(session.messages)
        kv.putString(KEY_ACTIVE_ARCHIVED_SESSION, session.id)
        return true
    }

    private fun trim(messages: List<AgentMessage>): List<AgentMessage> {
        if (maxMessages <= 0) return emptyList()
        return messages.takeLast(maxMessages)
    }

    private fun saveArchivedSessions(sessions: List<AgentConversationSession>) {
        val arr = JSONArray()
        sessions.forEach { session ->
            arr.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("createdAt", session.createdAt)
                    .put("updatedAt", session.updatedAt)
                    .put(
                        "messages",
                        JSONArray().also { messages ->
                            session.messages.forEach { msg ->
                                messages.put(messageJson(msg))
                            }
                        },
                    ),
            )
        }
        kv.putString(KEY_ARCHIVED_SESSIONS, arr.toString())
    }

    private fun messageJson(msg: AgentMessage): JSONObject =
        JSONObject()
            .put("id", msg.id)
            .put("role", msg.role.name)
            .put("content", msg.content)
            .put("createdAt", msg.createdAt)
            .put("status", msg.status.name)
            .put("toolSummary", msg.toolSummary)

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

    private fun parseSession(obj: JSONObject): AgentConversationSession? {
        val messagesArr = obj.optJSONArray("messages") ?: return null
        val messages = (0 until messagesArr.length()).mapNotNull { index ->
            messagesArr.optJSONObject(index)?.let(::parseMessage)
        }
        if (messages.isEmpty()) return null
        return AgentConversationSession(
            id = obj.optString("id").ifBlank { return null },
            title = obj.optString("title").ifBlank { "未命名对话" },
            createdAt = obj.optLong("createdAt"),
            updatedAt = obj.optLong("updatedAt"),
            messages = messages,
        )
    }

    companion object {
        private const val KEY_HISTORY = "agentHistory"
        private const val KEY_ARCHIVED_SESSIONS = "agentArchivedSessions"
        private const val KEY_ACTIVE_ARCHIVED_SESSION = "agentActiveArchivedSession"
        private const val DEFAULT_MAX_MESSAGES = 120
        private const val DEFAULT_MAX_ARCHIVED_SESSIONS = 20
        private const val DEFAULT_MAX_ARCHIVED_MESSAGES = 50
        private const val MAX_TITLE_CHARS = 28
    }
}
