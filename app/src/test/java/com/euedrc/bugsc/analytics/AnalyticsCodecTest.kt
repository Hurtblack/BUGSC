package com.euedrc.bugsc.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsCodecTest {

    @Test
    fun encodesAndDecodesEvents() {
        val events = listOf(
            AnalyticsEvent(
                eventName = "page_view",
                pageName = "tools",
                featureName = "",
                appVersion = "1.0.3",
                installId = "abc",
                timestampSeconds = 123L,
            ),
            AnalyticsEvent(
                eventName = "feature_click",
                pageName = "news",
                featureName = "open_link",
                appVersion = "1.0.3",
                installId = "abc",
                timestampSeconds = 124L,
            ),
        )

        val encoded = AnalyticsCodec.encode(events)
        val decoded = AnalyticsCodec.decode(encoded)

        assertEquals(events, decoded)
    }
}
