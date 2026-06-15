package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Test

class HangarTimerSyncSourceTest {

    @Test
    fun `parse xyxyll app js keeps initial open time as anchor`() {
        val script = """
            const CYCLE_DRIFT_MS = 226;
            const DESIGN_ONLINE_MIN  = 65;
            const DESIGN_OFFLINE_MIN = 120;
            const INITIAL_OPEN_TIME = new Date('2026-06-09T20:37:55.696-04:00');
        """.trimIndent()

        assertEquals(1781051875L, HangarTimerSyncSources.parseXyxyllAnchorSeconds(script))
    }

    @Test
    fun `authoritative candidate uses xyxyll all green anchor`() {
        val script = """
            const CYCLE_DRIFT_MS = 226;
            const DESIGN_ONLINE_MIN  = 65;
            const DESIGN_OFFLINE_MIN = 120;
            const INITIAL_OPEN_TIME = new Date('2026-06-09T20:37:55.696-04:00');
        """.trimIndent()

        val candidate = HangarTimerSyncSources.buildAuthoritativeCandidate(script)

        assertEquals("exec.xyxyll.com", candidate.name)
        assertEquals(listOf("green", "green", "green", "green", "green"), candidate.anchorLights)
        assertEquals(1781051875L, candidate.anchorSeconds)
    }

    @Test
    fun `choose source whose projected current anchor is closest to now`() {
        val nowSeconds = 1781202000L
        val chosen = HangarTimerSyncSources.chooseClosestToNow(
            nowSeconds = nowSeconds,
            candidates = listOf(
                HangarTimerSyncSources.SyncCandidate(
                    name = "older",
                    anchorSeconds = nowSeconds - 6000,
                    anchorLights = listOf("red", "red", "red", "red", "red"),
                    cycleSeconds = 7200.0
                ),
                HangarTimerSyncSources.SyncCandidate(
                    name = "newer",
                    anchorSeconds = nowSeconds - 7000,
                    anchorLights = listOf("green", "green", "green", "green", "green"),
                    cycleSeconds = 3600.0
                )
            )
        )

        assertEquals("newer", chosen.name)
        assertEquals(nowSeconds - 3400, chosen.projectedAnchorSeconds)
    }
}
