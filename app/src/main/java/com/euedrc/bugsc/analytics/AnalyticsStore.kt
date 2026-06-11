package com.euedrc.bugsc.analytics

import android.content.Context
import java.util.UUID

class AnalyticsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun installId(): String {
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    fun enqueue(event: AnalyticsEvent) {
        val updated = loadQueue().toMutableList().apply { add(event) }
        saveQueue(updated)
    }

    fun dequeueBatch(limit: Int): List<AnalyticsEvent> {
        val current = loadQueue()
        val batch = current.take(limit)
        if (batch.isNotEmpty()) saveQueue(current.drop(batch.size))
        return batch
    }

    fun prepend(events: List<AnalyticsEvent>) {
        if (events.isEmpty()) return
        saveQueue(events + loadQueue())
    }

    private fun loadQueue(): List<AnalyticsEvent> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return AnalyticsCodec.decode(raw)
    }

    private fun saveQueue(events: List<AnalyticsEvent>) {
        prefs.edit().putString(KEY_QUEUE, AnalyticsCodec.encode(events)).apply()
    }

    companion object {
        private const val PREFS_NAME = "analytics"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_QUEUE = "queue"
    }
}
