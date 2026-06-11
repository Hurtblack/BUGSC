package com.euedrc.bugsc

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class RsiStatusClient(
    private val fetchBody: (String) -> String = Companion::httpGet,
) {

    fun fetchStatus(): ToolHeaderStatus {
        return RsiStatusFeedParser.parse(fetchBody(STATUS_XML_URL))
    }

    companion object {
        private const val STATUS_XML_URL = "https://status.robertsspaceindustries.com/index.xml"
        private const val USER_AGENT = "SCMobiGlas-Android-App"

        private fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("状态源返回 ${conn.responseCode}")
                }
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
