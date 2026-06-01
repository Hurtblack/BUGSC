package com.euedrc.bugsc

import com.euedrc.bugsc.data.Bug
import com.euedrc.bugsc.data.Hardware
import org.junit.Assert.assertTrue
import org.junit.Test

class BugIssueCouncilFormatterTest {
    @Test
    fun formatsIssueCouncilTemplate() {
        val text = BugIssueCouncilFormatter.format(
            Bug(
                id = "local",
                title = "Elevator does not arrive",
                summary = "After calling the elevator, the marker appears but the elevator never opens.",
                description = "Happened twice in Area18.",
                typeTags = listOf("elevator", "area18"),
                severity = "high",
                hardware = Hardware(
                    gpuVendors = listOf("NVIDIA"),
                    vramMinGB = 8,
                    ramMinGB = 32,
                    cpuVendors = listOf("AMD")
                ),
                steps = listOf("Go to Area18 hab", "Call elevator", "Wait 60 seconds"),
                notes = "Relogging temporarily fixes it."
            )
        )

        assertTrue(text.contains("Title\nElevator does not arrive"))
        assertTrue(text.contains("Steps to Reproduce\n1. Go to Area18 hab\n2. Call elevator"))
        assertTrue(text.contains("GPU: NVIDIA"))
        assertTrue(text.contains("RAM: 32GB+"))
    }
}
