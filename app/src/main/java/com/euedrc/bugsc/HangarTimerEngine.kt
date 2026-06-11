package com.euedrc.bugsc

object HangarTimerEngine {

    data class TimerState(
        val lights: List<String>,
        val phase: Char,
        val remainingSeconds: Long,
    )

    fun computeStateByElapsed(
        anchors: List<String>,
        elapsed: Long,
        redToGreenSeconds: Long,
        greenToGraySeconds: Long,
        allGrayHoldSeconds: Long,
        defaultLights: List<String>,
    ): TimerState {
        var current = anchors.toMutableList()
        var remaining = elapsed

        while (true) {
            val phase = resolvePhase(current)
            val phaseTotal = phaseTotalSeconds(current, phase, redToGreenSeconds, greenToGraySeconds, allGrayHoldSeconds)

            if (remaining >= phaseTotal) {
                current = advanceWholePhase(current, phase, defaultLights)
                remaining -= phaseTotal
                continue
            }

            if (phase == 'A') {
                val passed = (remaining / redToGreenSeconds).toInt()
                current = applyTransitions(current, "red", "green", passed)
            } else {
                val greenCount = current.count { it == "green" }
                val greenWindow = greenCount * greenToGraySeconds
                current = if (remaining < greenWindow) {
                    val passed = (remaining / greenToGraySeconds).toInt()
                    applyTransitions(current, "green", "gray", passed)
                } else {
                    applyTransitions(current, "green", "gray", greenCount)
                }
            }

            return TimerState(
                lights = current.toList(),
                phase = resolvePhase(current),
                remainingSeconds = phaseTotal - remaining,
            )
        }
    }

    fun resolvePhase(lights: List<String>): Char = when {
        lights.any { it == "red" } -> 'A'
        else -> 'B'
    }

    fun phaseTotalSeconds(
        lights: List<String>,
        phase: Char,
        redToGreenSeconds: Long,
        greenToGraySeconds: Long,
        allGrayHoldSeconds: Long,
    ): Long = when (phase) {
        'A' -> lights.count { it == "red" }.toLong() * redToGreenSeconds
        else -> {
            val greenCount = lights.count { it == "green" }
            if (greenCount > 0) {
                greenCount.toLong() * greenToGraySeconds + allGrayHoldSeconds
            } else {
                allGrayHoldSeconds
            }
        }
    }

    fun advanceWholePhase(lights: MutableList<String>, phase: Char, defaultLights: List<String>): MutableList<String> {
        return when (phase) {
            'A' -> {
                val redCount = lights.count { it == "red" }
                applyTransitions(lights, "red", "green", redCount)
            }
            else -> {
                defaultLights.toMutableList()
            }
        }
    }

    fun applyTransitions(lights: MutableList<String>, from: String, to: String, steps: Int): MutableList<String> {
        val result = lights.toMutableList()
        var count = 0
        for (i in result.indices) {
            if (count >= steps) break
            if (result[i] == from) {
                result[i] = to
                count++
            }
        }
        return result
    }
}
