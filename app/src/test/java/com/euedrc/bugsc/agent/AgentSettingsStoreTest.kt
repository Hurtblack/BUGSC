package com.euedrc.bugsc.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsStoreTest {

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
    fun defaultsToDeepSeekFlashWithoutKey() {
        val store = AgentSettingsStore(FakeKv())
        val settings = store.settings()

        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, settings.model)
        assertEquals("", settings.apiKey)
        assertFalse(settings.isConfigured)
    }

    @Test
    fun saveSettingsRoundTripsApiKeyModelAndTestStatus() {
        val store = AgentSettingsStore(FakeKv())

        store.save(
            AgentSettings(
                apiKey = "sk-test",
                model = AgentSettingsStore.MODEL_DEEPSEEK_PRO,
                lastTestAt = 1780000000L,
                lastTestStatus = AgentConnectionStatus.SUCCESS,
            ),
        )

        val settings = store.settings()
        assertEquals("sk-test", settings.apiKey)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_PRO, settings.model)
        assertEquals(1780000000L, settings.lastTestAt)
        assertEquals(AgentConnectionStatus.SUCCESS, settings.lastTestStatus)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun blankModelFallsBackToDefault() {
        val kv = FakeKv()
        kv.putString("deepseekApiKey", "sk-test")
        kv.putString("deepseekModel", "")
        val store = AgentSettingsStore(kv)

        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, store.settings().model)
    }

    @Test
    fun clearRemovesApiKeyAndConnectionState() {
        val kv = FakeKv()
        val store = AgentSettingsStore(kv)
        store.save(
            AgentSettings(
                apiKey = "sk-test",
                model = AgentSettingsStore.MODEL_DEEPSEEK_PRO,
                lastTestAt = 1780000000L,
                lastTestStatus = AgentConnectionStatus.FAILURE,
            ),
        )

        store.clear()

        val settings = store.settings()
        assertEquals("", settings.apiKey)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, settings.model)
        assertEquals(0L, settings.lastTestAt)
        assertEquals(AgentConnectionStatus.NOT_TESTED, settings.lastTestStatus)
        assertFalse(settings.isConfigured)
    }
}
