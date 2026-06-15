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
                        put("imageUrls", JSONArray(item.imageUrls))
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
                            imageUrls = decodeImageUrls(obj),
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

    private fun decodeImageUrls(obj: JSONObject): List<String> {
        val imageUrls = obj.optJSONArray("imageUrls")
        if (imageUrls != null) {
            return buildList {
                for (i in 0 until imageUrls.length()) {
                    imageUrls.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        return listOfNotNull(
            obj.optString("thumbnailUrl")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
