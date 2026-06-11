package com.euedrc.bugsc.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsClientTest {

    @Test
    fun parsesNewsListAndBuildsFallbackSummary() {
        val json = """
            [
              {
                "title":"【每周简报】",
                "author":"CIG",
                "description":"上周，Alpha 4.8.1 正式上线。",
                "link":"https://tieba.baidu.com/p/10778659737",
                "pubDate":"2026-06-08T22:41:48.000Z",
                "postId":"10778659737",
                "detailedDescription":["<img src=\"https://example.com/a.jpg\" /><br><br>上周，Alpha 4.8.1 正式上线。<br>.<br>更多修复仍在进行中。"],
                "tag":"官方"
              },
              {
                "title":"【商店捆绑包】",
                "author":"CIG",
                "description":"",
                "link":"https://tieba.baidu.com/p/10780681312",
                "pubDate":"2026-06-09T16:39:37.000Z",
                "postId":"10780681312",
                "detailedDescription":["<img src=\"https://example.com/b.jpg\" /><br><br>六月商店捆绑包现已上线。"],
                "tag":"官方"
              }
            ]
        """.trimIndent()

        val items = NewsClient.parseNewsList(json)

        assertEquals(2, items.size)
        assertEquals("【每周简报】", items[0].title)
        assertEquals("上周，Alpha 4.8.1 正式上线。", items[0].summary)
        assertEquals("https://example.com/a.jpg", items[0].thumbnailUrl)
        assertEquals("六月商店捆绑包现已上线。", items[1].summary)
        assertEquals("https://example.com/b.jpg", items[1].thumbnailUrl)
    }

    @Test
    fun parsesPageInfo() {
        val json = """{"total":1095,"totalPages":73}"""

        val info = NewsClient.parsePageInfo(json)

        assertEquals(1095, info.total)
        assertEquals(73, info.totalPages)
    }

    @Test
    fun stripsHtmlAndCollapsesWhitespaceForSummary() {
        val summary = NewsClient.summaryFromHtml(
            "<img src=\"x\" /><br><br> Alpha 4.8.1  正式上线。 <br><h2>【本周】</h2><br> 更多修复仍在进行中。"
        )

        assertTrue(summary.contains("Alpha 4.8.1 正式上线。"))
        assertTrue(summary.contains("【本周】"))
        assertTrue(summary.contains("更多修复仍在进行中。"))
    }

    @Test
    fun extractsThumbnailFromDescriptionHtml() {
        val url = NewsClient.extractThumbnailUrl(
            description = "<img src=\"http://i0.hdslb.com/test.jpg\" referrerpolicy=\"no-referrer\">",
            detailedDescription = emptyList(),
        )

        assertEquals("http://i0.hdslb.com/test.jpg", url)
    }
}
