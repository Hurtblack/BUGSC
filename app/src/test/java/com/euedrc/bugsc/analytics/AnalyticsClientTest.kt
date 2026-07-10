package com.euedrc.bugsc.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsClientTest {

    @Test
    fun parsesEndpointListFromSingleBuildConfigValue() {
        val endpoints = AnalyticsClient.parseEndpoints(
            " https://analytics.example.com/collect,\n" +
                "https://cf.example/collect; https://analytics.example.com/collect ",
        )

        assertEquals(
            listOf(
                "https://analytics.example.com/collect",
                "https://cf.example/collect",
            ),
            endpoints,
        )
    }

    @Test
    fun disabledWhenNoEndpointConfigured() {
        val client = AnalyticsClient(endpoint = " , \n ; ")

        assertFalse(client.isEnabled())
    }

    @Test
    fun sendsToNextEndpointWhenEarlierEndpointFails() {
        val attempted = mutableListOf<String>()
        val client = AnalyticsClient(
            endpoint = "https://blocked.example/collect,https://analytics.example.com/collect",
            postJson = { url, _ ->
                attempted += url
                if (url.contains("blocked")) throw IllegalStateException("blocked")
            },
        )

        client.send(listOf(sampleEvent()))

        assertEquals(
            listOf("https://blocked.example/collect", "https://analytics.example.com/collect"),
            attempted,
        )
    }

    @Test
    fun throwsOnlyAfterAllEndpointsFail() {
        val client = AnalyticsClient(
            endpoint = "https://a.example/collect,https://b.example/collect",
            postJson = { _, _ -> throw IllegalStateException("blocked") },
        )

        val result = runCatching { client.send(listOf(sampleEvent())) }

        assertTrue(result.isFailure)
    }

    private fun sampleEvent(): AnalyticsEvent = AnalyticsEvent(
        eventName = "page_view",
        pageName = "tools",
        appVersion = "1.1.0",
        installId = "install",
        timestampSeconds = 1L,
    )
}
