package com.euedrc.bugsc.agent

class AgentPromptBuilder(private val profile: AgentProfile) {

    fun build(
        userText: String,
        history: List<AgentMessage>,
        skillResults: List<SkillResult>,
    ): List<DeepSeekMessage> {
        val system = buildString {
            appendLine("你是 ${profile.displayName} (${profile.codename})。")
            appendLine(profile.roleDescription)
            appendLine("定位：${profile.tagline}")
            appendLine("能力：${profile.capabilities.joinToString("；")}")
            appendLine("数据源：${profile.dataSources.joinToString("；")}")
            appendLine("隐私：${profile.privacyNotes.joinToString("；")}")
            appendLine("限制：${profile.limitations.joinToString("；")}")
            appendLine("请使用中文回答，优先依据工具结果。工具结果不足时必须说明不确定，不要编造地点、奖励、价格、材料和版本信息。")
        }
        val messages = mutableListOf(DeepSeekMessage("system", sanitize(system)))
        history.takeLast(MAX_HISTORY).forEach { msg ->
            val role = when (msg.role) {
                AgentMessageRole.ASSISTANT -> "assistant"
                AgentMessageRole.SYSTEM -> "system"
                AgentMessageRole.USER -> "user"
            }
            messages += DeepSeekMessage(role, sanitize(msg.content))
        }
        messages += DeepSeekMessage("user", sanitize(userText))
        if (skillResults.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatSkillResults(skillResults)))
        }
        return messages
    }

    private fun formatSkillResults(results: List<SkillResult>): String = buildString {
        appendLine("工具查询结果：")
        results.forEach { result ->
            appendLine("- Skill: ${result.skillId}")
            if (result.summary.isNotBlank()) appendLine("  摘要: ${result.summary}")
            if (result.error != null) appendLine("  错误: ${result.error}")
            result.facts.forEach { fact -> appendLine("  ${fact.label}: ${fact.value}") }
            if (result.sources.isNotEmpty()) {
                appendLine("  来源: ${result.sources.joinToString { it.name }}")
            }
        }
    }

    private fun sanitize(value: String): String {
        var text = value
        for (pattern in SENSITIVE_PATTERNS) {
            text = text.replace(pattern, "[已过滤]")
        }
        return text
    }

    companion object {
        private const val MAX_HISTORY = 6
        private val SENSITIVE_PATTERNS = listOf(
            Regex("""(?i)(api\s*key|apikey|deepseek\s*api\s*key)\s*[:=]?\s*\S+"""),
            Regex("""(?i)(authorization\s*:\s*bearer)\s+\S+"""),
            Regex("""(?i)(bearer)\s+\S+"""),
            Regex("""(?i)(scm\s*token|token)\s*[:=]?\s*\S+"""),
            Regex("""(?i)(cookie)\s*[:=]?\s*\S+"""),
            Regex("""sk-[A-Za-z0-9_-]+"""),
        )
    }
}
