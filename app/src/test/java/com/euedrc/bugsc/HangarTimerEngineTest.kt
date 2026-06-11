package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Test

class HangarTimerEngineTest {

    @Test
    fun allGrayHoldStaysInOpenPhase() {
        val state = HangarTimerEngine.computeStateByElapsed(
            anchors = listOf("green", "green", "green", "green", "green"),
            elapsed = 12L * 60L * 5L,
            redToGreenSeconds = 24L * 60L,
            greenToGraySeconds = 12L * 60L,
            allGrayHoldSeconds = 5L * 60L,
            defaultLights = listOf("red", "red", "red", "red", "red"),
        )

        assertEquals(listOf("gray", "gray", "gray", "gray", "gray"), state.lights)
        assertEquals('B', state.phase)
        assertEquals(5L * 60L, state.remainingSeconds)
    }

    @Test
    fun allGrayHoldExpiresBackToClosedPhase() {
        val state = HangarTimerEngine.computeStateByElapsed(
            anchors = listOf("green", "green", "green", "green", "green"),
            elapsed = (12L * 60L * 5L) + (5L * 60L),
            redToGreenSeconds = 24L * 60L,
            greenToGraySeconds = 12L * 60L,
            allGrayHoldSeconds = 5L * 60L,
            defaultLights = listOf("red", "red", "red", "red", "red"),
        )

        assertEquals(listOf("red", "red", "red", "red", "red"), state.lights)
        assertEquals('A', state.phase)
        assertEquals(24L * 60L * 5L, state.remainingSeconds)
    }

    @Test
    fun nextOpenFromClosedPhaseIsCurrentTransitionEnd() {
        val nextOpenAt = HangarTimerEngine.nextOpenAtSeconds(
            anchors = listOf("red", "red", "red", "red", "red"),
            anchorAt = 1_000L,
            nowSeconds = 1_000L + 10L,
            redToGreenSeconds = 24L * 60L,
            greenToGraySeconds = 12L * 60L,
            allGrayHoldSeconds = 5L * 60L,
            defaultLights = listOf("red", "red", "red", "red", "red"),
        )

        assertEquals((5L * 24L * 60L) - 10L, nextOpenAt - (1_000L + 10L))
    }

    @Test
    fun nextOpenFromOpenPhaseSkipsToNextCycle() {
        val nextOpenAt = HangarTimerEngine.nextOpenAtSeconds(
            anchors = listOf("green", "green", "green", "green", "green"),
            anchorAt = 2_000L,
            nowSeconds = 2_000L + 100L,
            redToGreenSeconds = 24L * 60L,
            greenToGraySeconds = 12L * 60L,
            allGrayHoldSeconds = 5L * 60L,
            defaultLights = listOf("red", "red", "red", "red", "red"),
        )

        val expectedRemaining =
            (5L * 12L * 60L + 5L * 60L - 100L) + (5L * 24L * 60L)
        assertEquals(expectedRemaining, nextOpenAt - (2_000L + 100L))
    }
}
