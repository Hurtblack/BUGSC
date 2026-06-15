package com.euedrc.bugsc.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsPaginationStateTest {

    @Test
    fun preventsDuplicateRequestsWhileLoading() {
        val state = NewsPaginationState()
        state.reset(totalPages = 3)

        assertEquals(1, state.beginNextPage())
        assertNull(state.beginNextPage())
        assertTrue(state.isLoading)
    }

    @Test
    fun retriesFailedPageWithoutSkipping() {
        val state = NewsPaginationState()
        state.reset(totalPages = 3)
        val page = state.beginNextPage()!!

        state.fail(page)

        assertFalse(state.canAutoLoad)
        assertEquals(1, state.beginNextPage())
    }

    @Test
    fun stopsAfterFinalPage() {
        val state = NewsPaginationState()
        state.reset(totalPages = 1)
        val page = state.beginNextPage()!!
        state.complete(page, listOf(item("1")))

        assertNull(state.beginNextPage())
        assertFalse(state.hasMorePages)
    }

    @Test
    fun appendsUniqueItemsInOriginalOrder() {
        val state = NewsPaginationState()
        state.reset(totalPages = 2)
        state.complete(
            page = state.beginNextPage()!!,
            items = listOf(item("1"), item("2")),
        )

        val appended = state.complete(
            page = state.beginNextPage()!!,
            items = listOf(item("2"), item("3")),
        )

        assertEquals(listOf("3"), appended.map(NewsClient.NewsItem::postId))
        assertEquals(2, state.currentPage)
    }

    @Test
    fun seedsCachedFirstPageBeforeRemoteRefresh() {
        val state = NewsPaginationState()

        state.seedFirstPage(listOf(item("cached")), totalPages = 4)

        assertEquals(1, state.currentPage)
        assertEquals(2, state.beginNextPage())
    }

    private fun item(postId: String) = NewsClient.NewsItem(
        title = "标题$postId",
        tag = "官方",
        author = "CIG",
        summary = "摘要",
        imageUrls = emptyList(),
        link = "https://example.com/$postId",
        pubDate = "",
        postId = postId,
    )
}
