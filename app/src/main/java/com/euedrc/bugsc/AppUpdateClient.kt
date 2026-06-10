package com.euedrc.bugsc

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateClient(
    private val sources: List<UpdateSource> = DEFAULT_SOURCES,
    private val fetchBody: (String) -> String = Companion::httpGet,
) {

    /** 一个更新检查源：名称 + latest-release API 地址 + 响应 JSON 解析器 */
    data class UpdateSource(
        val name: String,
        val apiUrl: String,
        val parse: (String) -> ReleaseInfo?,
    )

    data class ReleaseInfo(
        val versionName: String,
        val pageUrl: String,
        val apkUrl: String?,
        val notes: String,
    )

    /** 按顺序逐源尝试，首个成功者生效；全部失败抛出异常 */
    fun fetchLatestRelease(): ReleaseInfo? {
        var lastError: Throwable? = null
        for (source in sources) {
            val result = runCatching { source.parse(fetchBody(source.apiUrl)) }
            result.fold(
                onSuccess = { release -> if (release != null) return release },
                onFailure = { lastError = it },
            )
        }
        lastError?.let { throw IllegalStateException("所有更新源均失败", it) }
        return null
    }

    companion object {
        private const val USER_AGENT = "SCMobiGlas-Android-App"
        private const val GITHUB_LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/Hurtblack/BUGSC/releases/latest"
        private const val GITEE_REPO = "hurtblack/BUGSC"
        private const val GITEE_LATEST_RELEASE_API_URL =
            "https://gitee.com/api/v5/repos/$GITEE_REPO/releases/latest"

        // GitHub 为主源，国内访问 api.github.com 失败时回退 Gitee
        val DEFAULT_SOURCES = listOf(
            UpdateSource("GitHub", GITHUB_LATEST_RELEASE_API_URL, Companion::parseRelease),
            UpdateSource("Gitee", GITEE_LATEST_RELEASE_API_URL, Companion::parseGiteeRelease),
        )

        private fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("更新源返回 ${conn.responseCode}")
                }
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        /** GitHub latest-release 响应解析 */
        fun parseRelease(json: String): ReleaseInfo? {
            val root = JSONObject(json)
            val versionName = normalizeVersionName(root.optString("tag_name"))
                ?: normalizeVersionName(root.optString("name"))
                ?: return null
            val pageUrl = root.optString("html_url").takeIf { it.isNotBlank() } ?: return null
            return ReleaseInfo(versionName, pageUrl, pickApkUrl(root), root.optString("body"))
        }

        /** Gitee latest-release 响应解析：无 html_url 字段，页面地址按 tag 拼接 */
        fun parseGiteeRelease(json: String): ReleaseInfo? {
            val root = JSONObject(json)
            val versionName = normalizeVersionName(root.optString("tag_name"))
                ?: normalizeVersionName(root.optString("name"))
                ?: return null
            val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: "v$versionName"
            val pageUrl = "https://gitee.com/$GITEE_REPO/releases/tag/$tag"
            return ReleaseInfo(versionName, pageUrl, pickApkUrl(root), root.optString("body"))
        }

        /** 从 assets 数组中选 apk：优先文件名含 release 的，否则第一个 apk */
        private fun pickApkUrl(root: JSONObject): String? {
            val assets = root.optJSONArray("assets") ?: return null
            var preferredApkUrl: String? = null
            var fallbackApkUrl: String? = null
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
            return preferredApkUrl ?: fallbackApkUrl
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
