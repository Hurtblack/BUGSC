package com.euedrc.bugsc.analytics

import org.json.JSONArray
import org.json.JSONObject

object AnalyticsCodec {

    fun encode(events: List<AnalyticsEvent>): String {
        return toJsonArray(events).toString()
    }

    fun toJsonArray(events: List<AnalyticsEvent>): JSONArray = JSONArray().apply {
        events.forEach { event ->
            put(JSONObject().apply {
                put("event_name", event.eventName)
                put("page_name", event.pageName)
                put("feature_name", event.featureName)
                put("app_version", event.appVersion)
                put("install_id", event.installId)
                put("ts", event.timestampSeconds)
            })
        }
    }

    fun decode(raw: String): List<AnalyticsEvent> {
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        AnalyticsEvent(
                            eventName = obj.optString("event_name"),
                            pageName = obj.optString("page_name"),
                            featureName = obj.optString("feature_name"),
                            appVersion = obj.optString("app_version"),
                            installId = obj.optString("install_id"),
                            timestampSeconds = obj.optLong("ts"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
