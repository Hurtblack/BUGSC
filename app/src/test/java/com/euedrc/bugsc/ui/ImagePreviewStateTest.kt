package com.euedrc.bugsc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagePreviewStateTest {

    @Test
    fun dropsBlankUrlsAndKeepsRequestedImage() {
        val state = ImagePreviewState.create(
            urls = listOf("", "https://example.com/a.jpg", " https://example.com/b.jpg "),
            requestedIndex = 1,
        )

        assertEquals("https://example.com/b.jpg", state?.currentUrl)
        assertEquals("2/2", state?.indicator)
    }

    @Test
    fun clampsRequestedIndex() {
        val state = ImagePreviewState.create(
            urls = listOf("https://example.com/a.jpg"),
            requestedIndex = 12,
        )

        assertEquals("https://example.com/a.jpg", state?.currentUrl)
        assertEquals("", state?.indicator)
    }

    @Test
    fun returnsNullWhenNoUsableImageExists() {
        assertNull(ImagePreviewState.create(listOf(" ", ""), requestedIndex = 0))
    }
}
