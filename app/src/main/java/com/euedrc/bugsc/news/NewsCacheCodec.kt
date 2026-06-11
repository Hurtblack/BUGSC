package com.euedrc.bugsc.news

import org.json.JSONArray
import org.json.JSONObject

object NewsCacheCodec {

    data class CachedPage(
        val cachedAt: Long,
        val items: List<NewsClient.NewsItem>,
    )

    fun encode(items: List<NewsClient.NewsItem>, cachedAt: Long): String {
        return JSONObject().apply {
            put("cachedAt", cachedAt)
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("title", item.title)
                        put("tag", item.tag)
                        put("author", item.author)
                        put("summary", item.summary)
                        put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
                        put("link", item.link)
                        put("pubDate", item.pubDate)
                        put("postId", item.postId)
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String): CachedPage? {
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return null
            val items = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        NewsClient.NewsItem(
                            title = obj.optString("title"),
                            tag = obj.optString("tag"),
                            author = obj.optString("author"),
                            summary = obj.optString("summary"),
                            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            link = obj.optString("link"),
                            pubDate = obj.optString("pubDate"),
                            postId = obj.optString("postId"),
                        )
                    )
                }
            }
            CachedPage(
                cachedAt = root.optLong("cachedAt", 0L),
                items = items,
            )
        }.getOrNull()
    }
}
