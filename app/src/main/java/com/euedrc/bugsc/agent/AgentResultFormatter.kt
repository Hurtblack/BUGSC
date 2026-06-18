package com.euedrc.bugsc.agent

object AgentResultFormatter {

    fun hasUsefulData(result: SkillResult): Boolean =
        result.error == null && result.confidence > 0f && (result.facts.isNotEmpty() || !result.summary.contains("未命中"))

    fun hasUsefulData(result: AgentToolResult): Boolean =
        result.error == null && result.confidence > 0f && (result.facts.isNotEmpty() || !result.summary.contains("未命中"))

    fun formatUsefulResults(
        summaries: List<String>,
        facts: List<AgentFact>,
    ): String {
        val valuesByLabel = facts
            .filter { it.value.isNotBlank() }
            .groupBy { it.label }
            .mapValues { (_, values) -> values.map { it.value }.distinct() }
        if (valuesByLabel.keys.any { it in setOf("订单", "单价", "卖家") }) {
            return summaries
                .filter(String::isNotBlank)
                .flatMap { it.lineSequence().map(String::trim).filter(String::isNotBlank).toList() }
                .distinct()
                .take(6)
                .joinToString("\n")
        }
        val blueprints = valuesByLabel["蓝图"].orEmpty().take(3)
        if (blueprints.isNotEmpty()) {
            return buildString {
                appendSection("蓝图", blueprints)
                appendSection("材料", valuesByLabel["材料"].orEmpty().take(3))
                appendSection("来源", (valuesByLabel["任务来源"].orEmpty() + valuesByLabel["兑换"].orEmpty()).distinct().take(3))
                val firstSummary = summaries.firstOrNull { it.isNotBlank() }?.lineSequence()?.firstOrNull()?.trim()
                if (!firstSummary.isNullOrBlank()) appendSection("结论", listOf(firstSummary))
            }.trim()
        }
        val preferred = listOf("矿物", "船只", "任务", "兑换", "奖励", "地点", "推荐查询地点")
            .flatMap { label -> valuesByLabel[label].orEmpty().take(2).map { label to it } }
            .take(6)
        if (preferred.isNotEmpty()) {
            val headline = summaries
                .firstOrNull { it.isNotBlank() && !it.contains("未命中") }
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
            return (listOfNotNull(headline) + preferred.map { (label, value) -> "- $label：$value" })
                .distinct()
                .take(6)
                .joinToString("\n")
        }
        return summaries
            .filter { it.isNotBlank() && !it.contains("未命中") }
            .map { it.trim() }
            .distinct()
            .take(6)
            .joinToString("\n\n")
    }

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        val visible = lines.filter { it.isNotBlank() }.distinct()
        if (visible.isEmpty()) return
        if (isNotEmpty()) appendLine().appendLine()
        appendLine("### $title")
        visible.forEach { appendLine("- $it") }
    }
}
