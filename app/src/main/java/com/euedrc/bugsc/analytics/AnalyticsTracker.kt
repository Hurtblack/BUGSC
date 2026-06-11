package com.euedrc.bugsc.analytics

import android.content.Context
import com.euedrc.bugsc.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnalyticsTracker private constructor(
    context: Context,
    private val appVersion: String,
) {
    private val store = AnalyticsStore(context.applicationContext)
    private val client = AnalyticsClient(BuildConfig.ANALYTICS_URL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun trackAppOpen() = track("app_open")

    fun trackPageView(pageName: String) = track("page_view", pageName = pageName)

    fun trackFeatureClick(pageName: String, featureName: String) =
        track("feature_click", pageName = pageName, featureName = featureName)

    private fun track(eventName: String, pageName: String = "", featureName: String = "") {
        if (!client.isEnabled()) return
        val event = AnalyticsEvent(
            eventName = eventName,
            pageName = pageName,
            featureName = featureName,
            appVersion = appVersion,
            installId = store.installId(),
            timestampSeconds = System.currentTimeMillis() / 1000,
        )
        store.enqueue(event)
        flushAsync()
    }

    private fun flushAsync() {
        if (!client.isEnabled()) return
        scope.launch {
            val batch = store.dequeueBatch(limit = 20)
            if (batch.isEmpty()) return@launch
            runCatching { client.send(batch) }
                .onFailure { store.prepend(batch) }
        }
    }

    companion object {
        @Volatile
        private var instance: AnalyticsTracker? = null

        fun get(context: Context, appVersion: String = ""): AnalyticsTracker {
            return instance ?: synchronized(this) {
                instance ?: AnalyticsTracker(
                    context = context.applicationContext,
                    appVersion = appVersion.ifBlank { currentVersion(context) },
                ).also { instance = it }
            }
        }

        private fun currentVersion(context: Context): String {
            return runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }
    }
}
