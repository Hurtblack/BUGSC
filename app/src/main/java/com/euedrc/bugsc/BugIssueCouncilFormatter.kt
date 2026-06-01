package com.euedrc.bugsc

import com.euedrc.bugsc.data.Bug

object BugIssueCouncilFormatter {
    const val ISSUE_COUNCIL_URL = "https://issue-council.robertsspaceindustries.com/projects/STAR-CITIZEN"

    fun format(bug: Bug): String {
        val lines = mutableListOf<String>()
        lines += "Title"
        lines += bug.title
        lines += ""
        lines += "Summary"
        lines += bug.summary

        if (bug.description.isNotBlank()) {
            lines += ""
            lines += "Description"
            lines += bug.description
        }

        lines += ""
        lines += "Steps to Reproduce"
        bug.steps.forEachIndexed { index, step ->
            lines += "${index + 1}. $step"
        }

        lines += ""
        lines += "Expected Result"
        lines += "请在 IC 页面补充预期结果。"

        lines += ""
        lines += "Actual Result"
        lines += bug.summary

        val tags = bug.typeTags.joinToString(", ").ifBlank { "-" }
        lines += ""
        lines += "Environment"
        lines += "Severity: ${bug.severity}"
        lines += "Tags: $tags"
        lines += "GPU: ${bug.hardware.gpuVendors.joinToString(", ")}"
        lines += "CPU: ${bug.hardware.cpuVendors.joinToString(", ")}"
        lines += "VRAM: ${gbText(bug.hardware.vramMinGB)}"
        lines += "RAM: ${gbText(bug.hardware.ramMinGB)}"

        if (bug.notes.isNotBlank()) {
            lines += ""
            lines += "Additional Notes"
            lines += bug.notes
        }

        return lines.joinToString("\n").trim()
    }

    private fun gbText(value: Int): String = if (value > 0) "${value}GB+" else "-"
}
