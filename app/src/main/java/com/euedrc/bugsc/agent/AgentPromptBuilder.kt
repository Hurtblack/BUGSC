package com.euedrc.bugsc.agent

class AgentPromptBuilder(private val profile: AgentProfile) {

    fun build(
        userText: String,
        history: List<AgentMessage>,
        skillResults: List<SkillResult>,
        skillCards: List<AgentSkillCard> = emptyList(),
        toolResults: List<AgentToolResult> = emptyList(),
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
            appendLine("你没有可调用的 search、tool、function 或联网浏览能力；App 已经在调用模型前完成了可用查询。")
            appendLine("不要输出 <search>、<tool>、JSON 工具调用、伪代码查询或“我再查一下”。不能假装正在联网查询、登录查询或后台继续查询。")
            appendLine("资料未命中不等于不能回答；你可以继续基于游戏常识、问题语义和上下文做分析，但必须明确标注“本地资料未命中，以下是模型推断/建议”。")
            appendLine("如果用户问“怎么做、怎么弄、材料、蓝图、任务来源”，优先给出排查路径和下一步建议，不要只让用户换关键词。")
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
        if (skillCards.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatSkillCards(skillCards)))
        }
        if (toolResults.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatToolResults(toolResults)))
        }
        if (skillResults.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatSkillResults(skillResults)))
        }
        return messages
    }

    private fun formatSkillCards(cards: List<AgentSkillCard>): String = buildString {
        appendLine("已选择的 Skill 工作流：")
        cards.forEach { card ->
            appendLine("Skill 工作流：${card.id}")
            appendLine("标题：${card.title}")
            appendLine("流程：${card.workflow}")
            if (card.preferredTools.isNotEmpty()) {
                appendLine("工具策略：${card.preferredTools.joinToString(" -> ")}")
            }
        }
    }

    private fun formatToolResults(results: List<AgentToolResult>): String = buildString {
        appendLine("工具执行记录：")
        results.forEach { result ->
            appendLine("- Tool: ${result.call.tool}")
            if (result.call.args.isNotEmpty()) {
                appendLine("  参数: ${result.call.args.entries.joinToString { "${it.key}=${it.value}" }}")
            }
            if (result.call.reason.isNotBlank()) appendLine("  原因: ${result.call.reason}")
            if (result.summary.isNotBlank()) appendLine("  摘要: ${result.summary}")
            if (result.error != null) appendLine("  错误: ${result.error}")
            result.facts.forEach { fact -> appendLine("  ${fact.label}: ${fact.value}") }
            if (result.sources.isNotEmpty()) {
                appendLine("  来源: ${result.sources.joinToString { it.name }}")
            }
        }
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
