package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Test

class RsiStatusFeedParserTest {

    @Test
    fun marksLiveServicesDisruptionAsPuOutage() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Live Services Disruption</title>
                  <description>The Team is currently investigating elevated error rates preventing players from joining the Persistent Universe.</description>
                </item>
                <item>
                  <title>[Resolved] RSI Platform Maintenance</title>
                  <description>Resolved platform maintenance.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val status = RsiStatusFeedParser.parse(xml)

        assertEquals(ServiceStatusLevel.OPERATIONAL, status.platform)
        assertEquals(ServiceStatusLevel.OUTAGE, status.persistentUniverse)
        assertEquals(ServiceStatusLevel.OPERATIONAL, status.arenaCommander)
    }

    @Test
    fun marksPlatformMaintenanceAsPlatformDegraded() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>RSI Platform Maintenance</title>
                  <description>Launcher and website may be intermittently unavailable.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val status = RsiStatusFeedParser.parse(xml)

        assertEquals(ServiceStatusLevel.DEGRADED, status.platform)
        assertEquals(ServiceStatusLevel.OPERATIONAL, status.persistentUniverse)
        assertEquals(ServiceStatusLevel.OPERATIONAL, status.arenaCommander)
    }

    @Test
    fun marksArenaCommanderIssueSeparately() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <title>Arena Commander Service Disruption</title>
                  <description>Arena Commander matchmaking is unavailable.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val status = RsiStatusFeedParser.parse(xml)

        assertEquals(ServiceStatusLevel.OPERATIONAL, status.platform)
        assertEquals(ServiceStatusLevel.OPERATIONAL, status.persistentUniverse)
        assertEquals(ServiceStatusLevel.OUTAGE, status.arenaCommander)
    }
}
