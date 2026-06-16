package com.euedrc.bugsc

import android.content.Context
import org.json.JSONObject

data class CachedToolHeaderStatus(
    val status: ToolHeaderStatus,
    val updatedAt: Long,
)

object RsiStatusCacheCodec {
    fun encode(cached: CachedToolHeaderStatus): String =
        JSONObject()
            .put("updatedAt", cached.updatedAt)
            .put("platform", cached.status.platform.name)
            .put("persistentUniverse", cached.status.persistentUniverse.name)
            .put("arenaCommander", cached.status.arenaCommander.name)
            .toString()

    fun decode(value: String): CachedToolHeaderStatus? =
        runCatching {
            val json = JSONObject(value)
            CachedToolHeaderStatus(
                status = ToolHeaderStatus(
                    platform = ServiceStatusLevel.valueOf(json.getString("platform")),
                    persistentUniverse = ServiceStatusLevel.valueOf(json.getString("persistentUniverse")),
                    arenaCommander = ServiceStatusLevel.valueOf(json.getString("arenaCommander")),
                ),
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }.getOrNull()
}

class RsiStatusCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): CachedToolHeaderStatus? =
        prefs.getString(KEY_STATUS, null)?.let(RsiStatusCacheCodec::decode)

    fun save(status: ToolHeaderStatus, updatedAt: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_STATUS, RsiStatusCacheCodec.encode(CachedToolHeaderStatus(status, updatedAt)))
            .apply()
    }

    private companion object {
        private const val PREFS = "rsi_status_cache"
        private const val KEY_STATUS = "tool_header_status"
    }
}
