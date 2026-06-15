package com.euedrc.bugsc.news

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsMarqueeTimingTest {

    @Test
    fun calculatesDurationFromDistanceAndDensity() {
        assertEquals(
            2_000L,
            NewsMarqueeTiming.durationMillis(
                distancePx = 72,
                density = 2f,
                speedDpPerSecond = 18f,
            ),
        )
    }

    @Test
    fun keepsVeryShortOverflowReadable() {
        assertEquals(
            1_500L,
            NewsMarqueeTiming.durationMillis(
                distancePx = 4,
                density = 3f,
                speedDpPerSecond = 18f,
            ),
        )
    }

    @Test
    fun alwaysScrollsForwardFromStartToOverflow() {
        assertEquals(NewsMarqueeTiming.ScrollLeg(from = 0, to = 120), NewsMarqueeTiming.forwardLeg(120))
    }
}
