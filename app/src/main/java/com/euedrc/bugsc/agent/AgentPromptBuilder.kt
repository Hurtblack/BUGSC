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
            appendLine("面向中文玩家输出：名称优先用中文；有英文名时只作为“中文 / English”的补充。")
            appendLine("回答要短，只输出用户问题对应的结论和翻译后的关键资料。不要输出工具名、查询过程、未命中列表、数据源说明或无关建议。")
            appendLine("如果命中蓝图，按“### 蓝图”“### 材料”“### 来源”“### 结论”组织；没有内容的板块直接省略。")
            appendLine("每个板块最多 3 条要点；整段回答尽量控制在 10 行以内。")
            appendLine("资料未命中不等于不能回答；可以基于游戏常识给建议，但必须用一句话标注“本地资料未命中，以下是推断”。")
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
        val useful = results.filter { it.hasUsefulData() }
        val visible = useful.ifEmpty { results.take(3) }
        appendLine("可用工具证据：")
        visible.forEach { result ->
            appendLine("- ${result.call.tool}")
            if (result.summary.isNotBlank()) appendLine("  摘要: ${result.summary}")
            result.facts.filter { it.value.isNotBlank() }.take(8).forEach { fact -> appendLine("  ${fact.label}: ${fact.value}") }
        }
        if (useful.isEmpty()) appendLine("没有可靠命中时，最终回答只需简短说明未命中并询问一个可帮助定位的关键词。")
    }

    private fun formatSkillResults(results: List<SkillResult>): String = buildString {
        val useful = results.filter { it.hasUsefulData() }
        val visible = useful.ifEmpty { results.take(3) }
        appendLine("可用查询证据：")
        visible.forEach { result ->
            appendLine("- Skill: ${result.skillId}")
            if (result.summary.isNotBlank()) appendLine("  摘要: ${result.summary}")
            result.facts.filter { it.value.isNotBlank() }.take(8).forEach { fact -> appendLine("  ${fact.label}: ${fact.value}") }
        }
        if (useful.isEmpty()) appendLine("没有可靠命中时，最终回答只需简短说明未命中并询问一个可帮助定位的关键词。")
    }

    private fun AgentToolResult.hasUsefulData(): Boolean =
        error == null && confidence > 0f && (facts.isNotEmpty() || !summary.contains("未命中"))

    private fun SkillResult.hasUsefulData(): Boolean =
        error == null && confidence > 0f && (facts.isNotEmpty() || !summary.contains("未命中"))

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
