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
}
