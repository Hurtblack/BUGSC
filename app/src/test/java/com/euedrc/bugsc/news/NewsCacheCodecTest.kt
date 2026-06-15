package com.euedrc.bugsc.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NewsCacheCodecTest {

    @Test
    fun encodesAndDecodesFirstPageCache() {
        val items = listOf(
            NewsClient.NewsItem(
                title = "标题",
                tag = "官方",
                author = "CIG",
                summary = "摘要",
                imageUrls = listOf(
                    "https://example.com/a.jpg",
                    "https://example.com/b.jpg",
                ),
                link = "https://tieba.baidu.com/p/1",
                pubDate = "2026-06-08T22:41:48.000Z",
                postId = "1",
            )
        )

        val encoded = NewsCacheCodec.encode(items = items, cachedAt = 123456789L)
        val decoded = NewsCacheCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(123456789L, decoded?.cachedAt)
        assertEquals(items, decoded?.items)
    }

    @Test
    fun migratesLegacyThumbnailUrlToImageList() {
        val raw = """
            {
              "cachedAt": 123,
              "items": [{
                "title": "旧缓存",
                "tag": "官方",
                "author": "CIG",
                "summary": "摘要",
                "thumbnailUrl": "https://example.com/legacy.jpg",
                "link": "https://example.com/news",
                "pubDate": "2026-06-08T22:41:48.000Z",
                "postId": "legacy"
              }]
            }
        """.trimIndent()

        val decoded = NewsCacheCodec.decode(raw)

        assertEquals(
            listOf("https://example.com/legacy.jpg"),
            decoded?.items?.single()?.imageUrls,
        )
    }

    @Test
    fun returnsNullForBrokenCachePayload() {
        assertNull(NewsCacheCodec.decode("{broken"))
    }
}
