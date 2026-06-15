package com.euedrc.bugsc.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPrefsAgentKvStore(private val prefs: SharedPreferences) : AgentSettingsKvStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }
    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }
}

object AgentStores {
    private const val PREFS = "agent_settings"

    fun kv(context: Context): AgentSettingsKvStore =
        SharedPrefsAgentKvStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun settings(context: Context): AgentSettingsStore = AgentSettingsStore(kv(context))

    fun history(context: Context): AgentHistoryStore = AgentHistoryStore(kv(context))
}
