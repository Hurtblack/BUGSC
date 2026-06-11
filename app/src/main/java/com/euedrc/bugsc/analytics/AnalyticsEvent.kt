package com.euedrc.bugsc.analytics

data class AnalyticsEvent(
    val eventName: String,
    val pageName: String = "",
    val featureName: String = "",
    val appVersion: String,
    val installId: String,
    val timestampSeconds: Long,
)
