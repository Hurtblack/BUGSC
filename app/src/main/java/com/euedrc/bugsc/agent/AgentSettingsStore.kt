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

data class AgentSettings(
    val apiKey: String,
    val model: String,
    val lastTestAt: Long = 0L,
    val lastTestStatus: AgentConnectionStatus = AgentConnectionStatus.NOT_TESTED,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
}

class AgentSettingsStore(private val kv: AgentSettingsKvStore) {

    fun settings(): AgentSettings {
        val model = kv.getString(KEY_MODEL)?.takeIf { it.isNotBlank() } ?: MODEL_DEEPSEEK_FLASH
        val status = kv.getString(KEY_LAST_TEST_STATUS)
            ?.let { runCatching { AgentConnectionStatus.valueOf(it) }.getOrNull() }
            ?: AgentConnectionStatus.NOT_TESTED
        return AgentSettings(
            apiKey = kv.getString(KEY_API_KEY).orEmpty(),
            model = model,
            lastTestAt = kv.getString(KEY_LAST_TEST_AT)?.toLongOrNull() ?: 0L,
            lastTestStatus = status,
        )
    }

    fun save(settings: AgentSettings) {
        kv.putString(KEY_API_KEY, settings.apiKey)
        kv.putString(KEY_MODEL, settings.model.ifBlank { MODEL_DEEPSEEK_FLASH })
        kv.putString(KEY_LAST_TEST_AT, settings.lastTestAt.toString())
        kv.putString(KEY_LAST_TEST_STATUS, settings.lastTestStatus.name)
    }

    fun clear() {
        kv.remove(KEY_API_KEY)
        kv.remove(KEY_MODEL)
        kv.remove(KEY_LAST_TEST_AT)
        kv.remove(KEY_LAST_TEST_STATUS)
    }

    companion object {
        const val MODEL_DEEPSEEK_FLASH = "deepseek-v4-flash"
        const val MODEL_DEEPSEEK_PRO = "deepseek-v4-pro"

        const val KEY_API_KEY = "deepseekApiKey"
        const val KEY_MODEL = "deepseekModel"
        const val KEY_LAST_TEST_AT = "deepseekLastTestAt"
        const val KEY_LAST_TEST_STATUS = "deepseekLastTestStatus"
    }
}
