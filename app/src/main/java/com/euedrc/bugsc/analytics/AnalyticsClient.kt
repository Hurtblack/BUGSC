package com.euedrc.bugsc.analytics

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AnalyticsClient(
    private val endpoint: String,
) {

    fun isEnabled(): Boolean = endpoint.isNotBlank()

    fun send(events: List<AnalyticsEvent>) {
        if (events.isEmpty()) return
        require(isEnabled()) { "analytics endpoint not configured" }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SCMobiGlas-Android-App")
            val body = JSONObject().put("events", AnalyticsCodec.toJsonArray(events)).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("analytics returned ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
