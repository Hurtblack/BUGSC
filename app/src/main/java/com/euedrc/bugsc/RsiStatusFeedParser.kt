package com.euedrc.bugsc

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

enum class ServiceStatusLevel {
    OPERATIONAL,
    DEGRADED,
    OUTAGE,
}

data class ToolHeaderStatus(
    val platform: ServiceStatusLevel = ServiceStatusLevel.OPERATIONAL,
    val persistentUniverse: ServiceStatusLevel = ServiceStatusLevel.OPERATIONAL,
    val arenaCommander: ServiceStatusLevel = ServiceStatusLevel.OPERATIONAL,
)

object RsiStatusFeedParser {

    fun parse(xml: String): ToolHeaderStatus {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val items = doc.getElementsByTagName("item")
        var platform = ServiceStatusLevel.OPERATIONAL
        var pu = ServiceStatusLevel.OPERATIONAL
        var ac = ServiceStatusLevel.OPERATIONAL

        for (i in 0 until items.length) {
            val item = items.item(i) as? Element ?: continue
            val title = item.getChildText("title")
            val description = item.getChildText("description")
            if (title.contains("[Resolved]", ignoreCase = true)) continue

            val severity = classifySeverity(title, description)
            if (matchesPlatform(title, description)) {
                platform = maxSeverity(platform, severity)
            }
            if (matchesPersistentUniverse(title, description)) {
                pu = maxSeverity(pu, severity)
            }
            if (matchesArenaCommander(title, description)) {
                ac = maxSeverity(ac, severity)
            }
        }

        return ToolHeaderStatus(
            platform = platform,
            persistentUniverse = pu,
            arenaCommander = ac,
        )
    }

    private fun classifySeverity(title: String, description: String): ServiceStatusLevel {
        val text = "$title $description".lowercase()
        return when {
            text.contains("maintenance") || text.contains("intermittently") -> ServiceStatusLevel.DEGRADED
            text.contains("disruption") ||
                text.contains("outage") ||
                text.contains("unavailable") ||
                text.contains("preventing players") ||
                text.contains("error rates") -> ServiceStatusLevel.OUTAGE
            else -> ServiceStatusLevel.DEGRADED
        }
    }

    private fun matchesPlatform(title: String, description: String): Boolean {
        val text = "$title $description".lowercase()
        return text.contains("platform") ||
            text.contains("launcher") ||
            text.contains("website") ||
            text.contains("spectrum") ||
            text.contains("api ")
    }

    private fun matchesPersistentUniverse(title: String, description: String): Boolean {
        val text = "$title $description".lowercase()
        return text.contains("persistent universe") ||
            text.contains("live services") ||
            text.contains("live service") ||
            text.contains("joining shards") ||
            text.contains("player inventories")
    }

    private fun matchesArenaCommander(title: String, description: String): Boolean {
        val text = "$title $description".lowercase()
        return text.contains("arena commander") ||
            text.contains("ac matchmaking")
    }

    private fun maxSeverity(current: ServiceStatusLevel, incoming: ServiceStatusLevel): ServiceStatusLevel {
        return if (incoming.ordinal > current.ordinal) incoming else current
    }

    private fun Element.getChildText(tag: String): String {
        return getElementsByTagName(tag).item(0)?.textContent.orEmpty()
    }
}
