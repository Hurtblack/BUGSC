package com.euedrc.bugsc.analytics

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AnalyticsClient(
    private val endpoint: String,
    private val postJson: (String, String) -> Unit = Companion::postJson,
) {
    private val endpoints = parseEndpoints(endpoint)

    fun isEnabled(): Boolean = endpoints.isNotEmpty()

    fun send(events: List<AnalyticsEvent>) {
        if (events.isEmpty()) return
        require(isEnabled()) { "analytics endpoint not configured" }
        val body = JSONObject().put("events", AnalyticsCodec.toJsonArray(events)).toString()
        var lastError: Throwable? = null
        for (url in endpoints) {
            val result = runCatching { postJson(url, body) }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
        }
        throw IllegalStateException("all analytics endpoints failed", lastError)
    }

    companion object {
        fun parseEndpoints(raw: String): List<String> {
            return raw
                .split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        private fun postJson(url: String, body: String) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "SCMobiGlas-Android-App")
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("analytics returned ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
