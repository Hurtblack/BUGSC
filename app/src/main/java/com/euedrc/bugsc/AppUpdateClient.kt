package com.euedrc.bugsc

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateClient(
    private val apiUrl: String = LATEST_RELEASE_API_URL,
) {

    data class ReleaseInfo(
        val versionName: String,
        val pageUrl: String,
        val apkUrl: String?,
        val notes: String,
    )

    fun fetchLatestRelease(): ReleaseInfo? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", USER_AGENT)

            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("GitHub 返回 ${conn.responseCode}")
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT = "BUGSC-Android-App"
        private const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/Hurtblack/BUGSC/releases/latest"

        fun parseRelease(json: String): ReleaseInfo? {
            val root = JSONObject(json)
            val versionName = normalizeVersionName(root.optString("tag_name"))
                ?: normalizeVersionName(root.optString("name"))
                ?: return null
            val pageUrl = root.optString("html_url").takeIf { it.isNotBlank() } ?: return null
            val notes = root.optString("body")
            val assets = root.optJSONArray("assets")
            var preferredApkUrl: String? = null
            var fallbackApkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    if (fallbackApkUrl == null) fallbackApkUrl = url
                    if (name.contains("release", ignoreCase = true)) {
                        preferredApkUrl = url
                        break
                    }
                }
            }

            return ReleaseInfo(
                versionName = versionName,
                pageUrl = pageUrl,
                apkUrl = preferredApkUrl ?: fallbackApkUrl,
                notes = notes,
            )
        }

        fun isNewerVersion(current: String, remote: String): Boolean {
            val currentParts = versionParts(current)
            val remoteParts = versionParts(remote)
            val maxSize = maxOf(currentParts.size, remoteParts.size)
            for (i in 0 until maxSize) {
                val currentPart = currentParts.getOrElse(i) { 0 }
                val remotePart = remoteParts.getOrElse(i) { 0 }
                if (remotePart > currentPart) return true
                if (remotePart < currentPart) return false
            }
            return false
        }

        private fun normalizeVersionName(raw: String?): String? {
            val cleaned = raw.orEmpty().trim().removePrefix("v").removePrefix("V")
            return cleaned.takeIf { it.isNotBlank() }
        }

        private fun versionParts(versionName: String): List<Int> {
            val normalized = normalizeVersionName(versionName).orEmpty()
            return normalized
                .split('.')
                .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
        }
    }
}
