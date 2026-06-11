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
                thumbnailUrl = "https://example.com/a.jpg",
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
    fun returnsNullForBrokenCachePayload() {
        assertNull(NewsCacheCodec.decode("{broken"))
    }
}
