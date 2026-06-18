package com.euedrc.bugsc.agent

interface AgentSettingsKvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

enum class AgentConnectionStatus {
    NOT_TESTED,
    SUCCESS,
    FAILURE,
}

enum class AgentAuthMode {
    BEARER,
    API_KEY,
}

data class AgentProviderPreset(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultAuthMode: AgentAuthMode,
)

data class AgentSettings(
    val apiKey: String,
    val model: String,
    val providerId: String = AgentSettingsStore.PROVIDER_DEEPSEEK,
    val baseUrl: String = "",
    val authMode: AgentAuthMode = AgentAuthMode.BEARER,
    val lastTestAt: Long = 0L,
    val lastTestStatus: AgentConnectionStatus = AgentConnectionStatus.NOT_TESTED,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && effectiveBaseUrl.isNotBlank() && model.isNotBlank()
    val effectiveBaseUrl: String get() =
        baseUrl.ifBlank { AgentSettingsStore.providerPreset(providerId).defaultBaseUrl }
}

class AgentSettingsStore(private val kv: AgentSettingsKvStore) {

    fun settings(): AgentSettings {
        val providerId = kv.getString(KEY_PROVIDER)?.takeIf { it.isNotBlank() } ?: PROVIDER_DEEPSEEK
        val preset = providerPreset(providerId)
        val model = kv.getString(KEY_MODEL)?.takeIf { it.isNotBlank() } ?: preset.defaultModel
        val authMode = kv.getString(KEY_AUTH_MODE)
            ?.let { runCatching { AgentAuthMode.valueOf(it) }.getOrNull() }
            ?: preset.defaultAuthMode
        val status = kv.getString(KEY_LAST_TEST_STATUS)
            ?.let { runCatching { AgentConnectionStatus.valueOf(it) }.getOrNull() }
            ?: AgentConnectionStatus.NOT_TESTED
        return AgentSettings(
            apiKey = kv.getString(KEY_API_KEY).orEmpty(),
            providerId = providerId,
            model = model,
            baseUrl = kv.getString(KEY_BASE_URL).orEmpty().ifBlank { preset.defaultBaseUrl },
            authMode = authMode,
            lastTestAt = kv.getString(KEY_LAST_TEST_AT)?.toLongOrNull() ?: 0L,
            lastTestStatus = status,
        )
    }

    fun save(settings: AgentSettings) {
        kv.putString(KEY_API_KEY, settings.apiKey)
        val preset = providerPreset(settings.providerId)
        kv.putString(KEY_PROVIDER, settings.providerId.ifBlank { PROVIDER_DEEPSEEK })
        kv.putString(KEY_MODEL, settings.model.ifBlank { preset.defaultModel })
        kv.putString(KEY_BASE_URL, settings.effectiveBaseUrl)
        kv.putString(KEY_AUTH_MODE, settings.authMode.name)
        kv.putString(KEY_LAST_TEST_AT, settings.lastTestAt.toString())
        kv.putString(KEY_LAST_TEST_STATUS, settings.lastTestStatus.name)
    }

    fun clear() {
        kv.remove(KEY_API_KEY)
        kv.remove(KEY_PROVIDER)
        kv.remove(KEY_MODEL)
        kv.remove(KEY_BASE_URL)
        kv.remove(KEY_AUTH_MODE)
        kv.remove(KEY_LAST_TEST_AT)
        kv.remove(KEY_LAST_TEST_STATUS)
    }

    companion object {
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_KIMI = "kimi"
        const val PROVIDER_XIAOMI_MIMO = "xiaomi_mimo"
        const val PROVIDER_CUSTOM = "custom"

        const val BASE_URL_DEEPSEEK = "https://api.deepseek.com"
        const val BASE_URL_KIMI = "https://api.moonshot.ai/v1"
        const val BASE_URL_XIAOMI_MIMO = "https://api.xiaomimimo.com/v1"

        const val MODEL_DEEPSEEK_FLASH = "deepseek-v4-flash"
        const val MODEL_DEEPSEEK_PRO = "deepseek-v4-pro"
        const val MODEL_KIMI_K2_5 = "kimi-k2.5"
        const val MODEL_XIAOMI_MIMO_PRO = "mimo-v2.5-pro"

        const val KEY_API_KEY = "deepseekApiKey"
        const val KEY_PROVIDER = "agentProvider"
        const val KEY_MODEL = "deepseekModel"
        const val KEY_BASE_URL = "agentBaseUrl"
        const val KEY_AUTH_MODE = "agentAuthMode"
        const val KEY_LAST_TEST_AT = "deepseekLastTestAt"
        const val KEY_LAST_TEST_STATUS = "deepseekLastTestStatus"

        val PROVIDER_PRESETS: List<AgentProviderPreset> = listOf(
            AgentProviderPreset(PROVIDER_DEEPSEEK, "DeepSeek", BASE_URL_DEEPSEEK, MODEL_DEEPSEEK_FLASH, AgentAuthMode.BEARER),
            AgentProviderPreset(PROVIDER_KIMI, "Kimi / Moonshot", BASE_URL_KIMI, MODEL_KIMI_K2_5, AgentAuthMode.BEARER),
            AgentProviderPreset(PROVIDER_XIAOMI_MIMO, "Xiaomi MiMo", BASE_URL_XIAOMI_MIMO, MODEL_XIAOMI_MIMO_PRO, AgentAuthMode.API_KEY),
            AgentProviderPreset(PROVIDER_CUSTOM, "OpenAI-Compatible", "", "", AgentAuthMode.BEARER),
        )

        fun providerPreset(id: String): AgentProviderPreset =
            PROVIDER_PRESETS.firstOrNull { it.id == id } ?: PROVIDER_PRESETS.first()
    }
}
