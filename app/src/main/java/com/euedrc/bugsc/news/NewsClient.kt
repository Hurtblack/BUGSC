package com.euedrc.bugsc.news

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NewsClient(
    private val postBody: (String, String) -> String = Companion::httpPostJson,
) {

    data class NewsItem(
        val title: String,
        val tag: String,
        val author: String,
        val summary: String,
        val thumbnailUrl: String?,
        val link: String,
        val pubDate: String,
        val postId: String,
    )

    data class PageInfo(
        val total: Int,
        val totalPages: Int,
    )

    fun fetchNewsPage(pageNumber: Int, searchText: String = "", messageType: String = DEFAULT_MESSAGE_TYPE): List<NewsItem> {
        val payload = JSONObject().apply {
            put("message_type", messageType)
            put("page_number", pageNumber)
            put("search_text", searchText)
        }
        return parseNewsList(postBody(NEWS_API_URL, payload.toString()))
    }

    fun fetchPageInfo(searchText: String = "", messageType: String = DEFAULT_MESSAGE_TYPE): PageInfo {
        val payload = JSONObject().apply {
            put("message_type", messageType)
            put("search_text", searchText)
        }
        return parsePageInfo(postBody(PAGE_INFO_API_URL, payload.toString()))
    }

    companion object {
        private const val USER_AGENT = "SCMobiGlas-Android-App"
        private const val NEWS_API_URL = "https://news.citizenwiki.cn/v2/api/news"
        private const val PAGE_INFO_API_URL = "https://news.citizenwiki.cn/v2/api/page_size"
        private const val DEFAULT_MESSAGE_TYPE = "新闻"

        fun parseNewsList(json: String): List<NewsItem> {
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val link = obj.optString("link").trim()
                    val title = obj.optString("title").trim()
                    if (title.isBlank() || link.isBlank()) continue
                    val description = obj.optString("description").trim()
                    val detailed = jsonArrayToList(obj.optJSONArray("detailedDescription"))
                    val summary = obj.optString("description").trim().takeIf { it.isNotBlank() }
                        ?: summaryFromHtml(detailed.joinToString("\n"))
                    add(
                        NewsItem(
                            title = title,
                            tag = obj.optString("tag").trim(),
                            author = obj.optString("author").trim(),
                            summary = summary,
                            thumbnailUrl = extractThumbnailUrl(description, detailed),
                            link = link,
                            pubDate = obj.optString("pubDate").trim(),
                            postId = obj.optString("postId").trim(),
                        )
                    )
                }
            }
        }

        fun parsePageInfo(json: String): PageInfo {
            val obj = JSONObject(json)
            return PageInfo(
                total = obj.optInt("total", 0),
                totalPages = obj.optInt("totalPages", 0),
            )
        }

        fun summaryFromHtml(html: String): String {
            return html
                .replace(Regex("<img\\b[^>]*>"), " ")
                .replace(Regex("<iframe\\b[^>]*>.*?</iframe>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</?(h1|h2|h3|p|div|li|ul|ol)[^>]*>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
                .replace(" . ", " ")
                .trim()
        }

        fun extractThumbnailUrl(description: String, detailedDescription: List<String>): String? {
            return extractFirstImageUrl(description)
                ?: detailedDescription.firstNotNullOfOrNull { extractFirstImageUrl(it) }
        }

        private fun extractFirstImageUrl(html: String): String? {
            val match = Regex("""<img\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: return null
            return normalizeImageUrl(match)
        }

        private fun normalizeImageUrl(raw: String): String {
            return when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "https://news.citizenwiki.cn$raw"
                else -> raw
            }
        }

        private fun jsonArrayToList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val part = arr.optString(i).trim()
                    if (part.isNotBlank()) add(part)
                }
            }
        }

        private fun httpPostJson(url: String, body: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("资讯源返回 ${conn.responseCode}")
                }
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
