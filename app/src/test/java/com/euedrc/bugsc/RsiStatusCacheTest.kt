package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RsiStatusCacheTest {

    @Test
    fun codecRoundTripsStatus() {
        val cached = CachedToolHeaderStatus(
            status = ToolHeaderStatus(
                platform = ServiceStatusLevel.OPERATIONAL,
                persistentUniverse = ServiceStatusLevel.DEGRADED,
                arenaCommander = ServiceStatusLevel.OUTAGE,
            ),
            updatedAt = 1234L,
        )

        val decoded = RsiStatusCacheCodec.decode(RsiStatusCacheCodec.encode(cached))

        assertEquals(cached, decoded)
    }

    @Test
    fun codecReturnsNullForInvalidJsonOrUnknownLevel() {
        assertNull(RsiStatusCacheCodec.decode("not json"))
        assertNull(
            RsiStatusCacheCodec.decode(
                """{"updatedAt":1,"platform":"BROKEN","persistentUniverse":"OPERATIONAL","arenaCommander":"OUTAGE"}""",
            ),
        )
    }
}
