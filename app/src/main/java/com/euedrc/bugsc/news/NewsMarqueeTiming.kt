package com.euedrc.bugsc.news

import kotlin.math.roundToLong

internal object NewsMarqueeTiming {
    private const val MIN_DURATION_MILLIS = 1_500L

    data class ScrollLeg(val from: Int, val to: Int)

    fun forwardLeg(overflowPixels: Int): ScrollLeg =
        ScrollLeg(from = 0, to = overflowPixels.coerceAtLeast(0))

    fun durationMillis(
        distancePx: Int,
        density: Float,
        speedDpPerSecond: Float,
    ): Long {
        val pixelsPerSecond = density * speedDpPerSecond
        if (pixelsPerSecond <= 0f) return MIN_DURATION_MILLIS
        return ((distancePx / pixelsPerSecond) * 1_000f)
            .roundToLong()
            .coerceAtLeast(MIN_DURATION_MILLIS)
    }
}
