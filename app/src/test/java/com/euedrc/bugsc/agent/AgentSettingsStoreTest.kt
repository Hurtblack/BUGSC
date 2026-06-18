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

        assertEquals(AgentSettingsStore.PROVIDER_DEEPSEEK, settings.providerId)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, settings.model)
        assertEquals(AgentSettingsStore.BASE_URL_DEEPSEEK, settings.effectiveBaseUrl)
        assertEquals(AgentAuthMode.BEARER, settings.authMode)
        assertEquals("", settings.apiKey)
        assertFalse(settings.isConfigured)
    }

    @Test
    fun saveSettingsRoundTripsApiKeyModelAndTestStatus() {
        val store = AgentSettingsStore(FakeKv())

        store.save(
            AgentSettings(
                apiKey = "sk-test",
                providerId = AgentSettingsStore.PROVIDER_KIMI,
                model = AgentSettingsStore.MODEL_DEEPSEEK_PRO,
                baseUrl = AgentSettingsStore.BASE_URL_KIMI,
                authMode = AgentAuthMode.BEARER,
                lastTestAt = 1780000000L,
                lastTestStatus = AgentConnectionStatus.SUCCESS,
            ),
        )

        val settings = store.settings()
        assertEquals("sk-test", settings.apiKey)
        assertEquals(AgentSettingsStore.PROVIDER_KIMI, settings.providerId)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_PRO, settings.model)
        assertEquals(AgentSettingsStore.BASE_URL_KIMI, settings.baseUrl)
        assertEquals(AgentSettingsStore.BASE_URL_KIMI, settings.effectiveBaseUrl)
        assertEquals(AgentAuthMode.BEARER, settings.authMode)
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
    fun legacyDeepSeekKeysStillLoadAsDeepSeekProvider() {
        val kv = FakeKv()
        kv.putString("deepseekApiKey", "sk-legacy")
        kv.putString("deepseekModel", AgentSettingsStore.MODEL_DEEPSEEK_PRO)

        val settings = AgentSettingsStore(kv).settings()

        assertEquals("sk-legacy", settings.apiKey)
        assertEquals(AgentSettingsStore.PROVIDER_DEEPSEEK, settings.providerId)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_PRO, settings.model)
        assertEquals(AgentSettingsStore.BASE_URL_DEEPSEEK, settings.effectiveBaseUrl)
    }

    @Test
    fun customProviderRequiresExplicitBaseUrl() {
        val settings = AgentSettings(
            apiKey = "sk-custom",
            providerId = AgentSettingsStore.PROVIDER_CUSTOM,
            model = "custom-model",
            baseUrl = "https://llm.example.com/v1",
            authMode = AgentAuthMode.API_KEY,
        )

        assertTrue(settings.isConfigured)
        assertEquals("https://llm.example.com/v1", settings.effectiveBaseUrl)
    }

    @Test
    fun clearRemovesApiKeyAndConnectionState() {
        val kv = FakeKv()
        val store = AgentSettingsStore(kv)
        store.save(
            AgentSettings(
                apiKey = "sk-test",
                providerId = AgentSettingsStore.PROVIDER_XIAOMI_MIMO,
                model = AgentSettingsStore.MODEL_DEEPSEEK_PRO,
                baseUrl = AgentSettingsStore.BASE_URL_XIAOMI_MIMO,
                authMode = AgentAuthMode.API_KEY,
                lastTestAt = 1780000000L,
                lastTestStatus = AgentConnectionStatus.FAILURE,
            ),
        )

        store.clear()

        val settings = store.settings()
        assertEquals("", settings.apiKey)
        assertEquals(AgentSettingsStore.PROVIDER_DEEPSEEK, settings.providerId)
        assertEquals(AgentSettingsStore.MODEL_DEEPSEEK_FLASH, settings.model)
        assertEquals(AgentSettingsStore.BASE_URL_DEEPSEEK, settings.effectiveBaseUrl)
        assertEquals(AgentAuthMode.BEARER, settings.authMode)
        assertEquals(0L, settings.lastTestAt)
        assertEquals(AgentConnectionStatus.NOT_TESTED, settings.lastTestStatus)
        assertFalse(settings.isConfigured)
    }
}
