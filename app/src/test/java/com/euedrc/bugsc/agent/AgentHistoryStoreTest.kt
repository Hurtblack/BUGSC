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
}
