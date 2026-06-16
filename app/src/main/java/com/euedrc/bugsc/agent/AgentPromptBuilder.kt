package com.euedrc.bugsc.agent

import org.json.JSONArray

class AgentPromptBuilder(private val profile: AgentProfile) {

    fun build(
        userText: String,
        history: List<AgentMessage>,
        skillResults: List<SkillResult>,
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
            appendLine("订单类请求只协助整理订单草稿。缺少出售或求购、物品、数量、单价、交易地点时，只追问缺失字段，不要猜测。")
            appendLine("字段齐全时只能提示已生成订单草稿并等待用户点击确认；真正创建订单必须由 App 在用户点击确认后执行。")
            appendLine("不要声称订单已创建、已发布、已挂单，除非 App 明确返回创建成功结果。")
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
        if (toolResults.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatToolResults(toolResults)))
        }
        if (skillResults.isNotEmpty()) {
            messages += DeepSeekMessage("system", sanitize(formatSkillResults(skillResults)))
        }
        return messages
    }

    fun buildToolCalling(
        userText: String,
        history: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        loopMessages: List<DeepSeekMessage>,
    ): List<DeepSeekMessage> {
        val system = buildString {
            appendLine("你是 MobiGuide，一个 Star Citizen 资料、SCM 交易和 SCMobiGlas App 能力助手。")
            appendLine("你不能编造工具结果。")
            appendLine("如果需要查询资料，必须调用工具。")
            appendLine("你每次只能输出一个 JSON 对象，不能输出 Markdown，不能输出解释文字。")
            appendLine()
            appendLine("你有两种输出类型：")
            appendLine("""{"type":"tool_call","tool":"工具名称","arguments":{}}""")
            appendLine("""{"type":"final_answer","answer":"中文回答"}""")
            appendLine()
            appendLine("重要规则：")
            appendLine("- 不允许输出“我去查一下”“稍等我查询”这种自然语言。")
            appendLine("- 不允许伪造工具结果。")
            appendLine("- 不允许调用不存在的工具。")
            appendLine("- 遇到出售、求购、挂单、创建订单、发布订单等请求时，必须调用 draft_scm_order 生成订单草稿。")
            appendLine("- 订单请求里的“品质850”“质量 900”不是物品名的一部分，调用 draft_scm_order 时必须拆成 quality 参数。")
            appendLine("- 用户询问“我要买/现在多少钱/哪里买”时，调用 search_market 且 side=sell，查询当前出售挂单。")
            appendLine("- 用户询问“我要卖/能卖多少钱/有人收吗”时，调用 search_market 且 side=buy，查询当前求购挂单。")
            appendLine("- 市场列表意图：用户没有指定具体物品，只是在问当前 SCM 市场有哪些订单、有什么货、有什么卖单、在售列表、卖家挂了什么等出售列表时，调用 search_market，arguments 必须包含 query=\"\" 和 side=\"sell\"。")
            appendLine("- 市场列表意图：用户没有指定具体物品，只是在问当前 SCM 市场有哪些收单、求购列表、有人收什么、买家要什么等求购列表时，调用 search_market，arguments 必须包含 query=\"\" 和 side=\"buy\"。")
            appendLine("- 市场追问意图：用户在市场查询后追问“谁卖”“谁收”“卖家是谁”“在哪交易”“哪个订单”等卖家、买家、地点或订单详情时，先从最近一条市场工具结果/历史回答中回答；如果历史里缺少该字段，必须复用最近的市场关键词调用 search_market 补查。")
            appendLine("- 市场问题以 search_market 结果为准；如果 search_market 未命中，就输出 final_answer 说明市场暂无对应出售/求购挂单，不要继续调用 search_scm_item、search_local_index、search_mining 等不相关工具。")
            appendLine("- 不允许直接创建订单，只能创建订单草稿。")
            appendLine("- 订单提交必须等待用户确认。")
            appendLine("- 工具参数必须符合对应 JSON Schema。")
            appendLine("- 当工具结果不足以回答问题时，可以继续调用另一个工具。")
            appendLine("- 收到有效 tool_result 后，如果已经足以回答用户问题，必须输出 final_answer；不要重复调用同一个工具和同一组参数。")
            appendLine("- 当已经有足够信息时，输出 final_answer。")
            appendLine()
            appendLine("可用工具：")
            appendLine(JSONArray(tools.map { it.toJson() }).toString())
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
        loopMessages.forEach { messages += DeepSeekMessage(it.role, sanitize(it.content)) }
        return messages
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
