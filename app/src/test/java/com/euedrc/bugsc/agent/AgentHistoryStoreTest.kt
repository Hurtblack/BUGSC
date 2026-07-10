package com.euedrc.bugsc.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHistoryStoreTest {

    private class FakeKv : AgentSettingsKvStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun saveAndLoadMessagesRoundTrip() {
        val store = AgentHistoryStore(FakeKv())
        val messages = listOf(
            AgentMessage("u1", AgentMessageRole.USER, "量子矿哪里采", 1L, AgentMessageStatus.SENT),
            AgentMessage("a1", AgentMessageRole.ASSISTANT, "Lyria", 2L, AgentMessageStatus.FAILED, "mining"),
        )

        store.save(messages)

        assertEquals(messages, store.load())
    }

    @Test
    fun clearRemovesHistory() {
        val store = AgentHistoryStore(FakeKv())
        store.save(listOf(AgentMessage("u1", AgentMessageRole.USER, "hi", 1L, AgentMessageStatus.SENT)))

        store.clear()

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun saveAndAppendKeepOnlyMostRecentMessages() {
        val store = AgentHistoryStore(FakeKv(), maxMessages = 3)

        store.save(
            listOf(
                AgentMessage("1", AgentMessageRole.USER, "1", 1L, AgentMessageStatus.SENT),
                AgentMessage("2", AgentMessageRole.ASSISTANT, "2", 2L, AgentMessageStatus.SENT),
                AgentMessage("3", AgentMessageRole.USER, "3", 3L, AgentMessageStatus.SENT),
                AgentMessage("4", AgentMessageRole.ASSISTANT, "4", 4L, AgentMessageStatus.SENT),
            ),
        )
        store.append(AgentMessage("5", AgentMessageRole.USER, "5", 5L, AgentMessageStatus.SENT))

        assertEquals(listOf("3", "4", "5"), store.load().map { it.id })
    }

    @Test
    fun appendIsSynchronizedSoConcurrentWritersDoNotLoseMessages() {
        val store = AgentHistoryStore(FakeKv(), maxMessages = 20)
        val threads = (1..12).map { index ->
            Thread {
                store.append(
                    AgentMessage(
                        id = index.toString(),
                        role = AgentMessageRole.USER,
                        content = "message $index",
                        createdAt = index.toLong(),
                        status = AgentMessageStatus.SENT,
                    ),
                )
            }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals((1..12).map { it.toString() }.sorted(), store.load().map { it.id }.sorted())
    }

    @Test
    fun archiveCurrentConversationStoresTitleMessagesAndToolSummary() {
        val store = AgentHistoryStore(FakeKv(), maxArchivedSessions = 5, maxArchivedMessages = 10)
        store.save(
            listOf(
                AgentMessage("u1", AgentMessageRole.USER, "科粒晶现在多少钱", 1L, AgentMessageStatus.SENT),
                AgentMessage("a1", AgentMessageRole.ASSISTANT, "1200 aUEC", 2L, AgentMessageStatus.SENT, "search_market page=1"),
            ),
        )

        val archived = store.archiveCurrentConversation(now = 3L)

        assertEquals(true, archived)
        val sessions = store.loadArchivedSessions()
        assertEquals(1, sessions.size)
        assertEquals("科粒晶现在多少钱", sessions.single().title)
        assertEquals("search_market page=1", sessions.single().messages.last().toolSummary)
    }

    @Test
    fun archiveCurrentConversationSkipsEmptyOrAssistantOnlyGreeting() {
        val store = AgentHistoryStore(FakeKv())
        store.save(listOf(AgentMessage("a1", AgentMessageRole.ASSISTANT, "你好", 1L, AgentMessageStatus.SENT)))

        val archived = store.archiveCurrentConversation(now = 2L)

        assertEquals(false, archived)
        assertTrue(store.loadArchivedSessions().isEmpty())
    }

    @Test
    fun archiveCurrentConversationKeepsMostRecentSessionsAndMessages() {
        val store = AgentHistoryStore(FakeKv(), maxArchivedSessions = 2, maxArchivedMessages = 2)
        repeat(3) { index ->
            store.save(
                listOf(
                    AgentMessage("u$index-1", AgentMessageRole.USER, "会话 $index 第一条", index * 10L + 1, AgentMessageStatus.SENT),
                    AgentMessage("a$index", AgentMessageRole.ASSISTANT, "回答 $index", index * 10L + 2, AgentMessageStatus.SENT),
                    AgentMessage("u$index-2", AgentMessageRole.USER, "会话 $index 第二条", index * 10L + 3, AgentMessageStatus.SENT),
                ),
            )
            store.archiveCurrentConversation(now = index.toLong())
        }

        val sessions = store.loadArchivedSessions()
        assertEquals(listOf("会话 2 第一条", "会话 1 第一条"), sessions.map { it.title })
        assertEquals(listOf("a2", "u2-2"), sessions.first().messages.map { it.id })
    }

    @Test
    fun restoreArchivedSessionReplacesCurrentConversation() {
        val store = AgentHistoryStore(FakeKv())
        store.save(listOf(AgentMessage("u1", AgentMessageRole.USER, "查市场", 1L, AgentMessageStatus.SENT)))
        store.archiveCurrentConversation(now = 2L)
        val sessionId = store.loadArchivedSessions().single().id
        store.save(listOf(AgentMessage("u2", AgentMessageRole.USER, "新的", 3L, AgentMessageStatus.SENT)))

        val restored = store.restoreArchivedSession(sessionId)

        assertEquals(true, restored)
        assertEquals(listOf("u1"), store.load().map { it.id })
    }

    @Test
    fun archiveRestoredSessionUpdatesExistingArchiveInsteadOfDuplicatingIt() {
        val store = AgentHistoryStore(FakeKv())
        store.save(listOf(AgentMessage("u1", AgentMessageRole.USER, "查市场", 1L, AgentMessageStatus.SENT)))
        store.archiveCurrentConversation(now = 2L)
        val sessionId = store.loadArchivedSessions().single().id

        store.restoreArchivedSession(sessionId)
        store.append(AgentMessage("a1", AgentMessageRole.ASSISTANT, "市场结果", 3L, AgentMessageStatus.SENT))
        val archived = store.archiveCurrentConversation(now = 4L)

        val sessions = store.loadArchivedSessions()
        assertEquals(true, archived)
        assertEquals(1, sessions.size)
        assertEquals(sessionId, sessions.single().id)
        assertEquals(listOf("u1", "a1"), sessions.single().messages.map { it.id })
        assertEquals(4L, sessions.single().updatedAt)
    }
}
