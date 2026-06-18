package com.euedrc.bugsc.agent

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
            appendLine("人设：${profile.persona.joinToString("；")}")
            appendLine("能力：${profile.capabilities.joinToString("；")}")
            appendLine("数据源：${profile.dataSources.joinToString("；")}")
            appendLine("隐私：${profile.privacyNotes.joinToString("；")}")
            appendLine("限制：${profile.limitations.joinToString("；")}")
            appendLine("请使用中文回答，优先依据工具结果。工具结果不足时必须说明不确定，不要编造地点、奖励、价格、材料和版本信息。")
            appendLine("当前回答只能依据本轮已经提供给你的查询证据和工具结果；不要编造未提供的后台查询、登录查询或联网结果。")
            appendLine("不要输出 <search>、<tool>、JSON 工具调用、伪代码查询或“我再查一下”。不能假装正在联网查询、登录查询或后台继续查询。")
            appendLine("面向中文玩家输出：名称优先用中文；有英文名时只作为“中文 / English”的补充。")
            appendLine("回答要像自然对话：先回应用户真正想问的事，再给结论和关键资料。不要输出工具名、查询过程、未命中列表、数据源说明或无关建议。")
            appendLine("回答格式要清晰：不同板块之间留一个空行；列表逐行输出；不要把挂单、交易、价格、地点挤在同一大段里。")
            appendLine("如果命中蓝图，按“### 蓝图”“### 材料”“### 来源”“### 结论”组织；没有内容的板块直接省略。")
            appendLine("每个板块最多 3 条要点；整段回答尽量控制在 10 行以内。")
            appendLine("资料未命中不等于不能回答；能基于通用常识、上下文或已给证据回答时先直接回答，只有完全无法回答时才简短说明资料未命中。不要在已有结论后追加“没查到可靠资料”一类收尾。")
            appendLine("订单类请求只协助整理订单草稿。缺少出售或求购、物品、数量、单价、交易地点时，只追问缺失字段，不要猜测。")
            appendLine("字段齐全时只能提示已生成订单草稿并等待用户点击确认；真正创建订单必须由 App 在用户点击确认后执行。")
            appendLine("不要声称订单已创建、已发布、已挂单，除非 App 明确返回创建成功结果。")
            appendLine("WBCCU 规则：CCU 是船只升级券，只能从低价值船升到高价值船；WB/Warbond 通常是现金优惠，不等同于可用商店点数。")
            appendLine("当用户要求 RSI 机库、WBCCU、CCU 链路或升级路线规划时，必须优先依据 get_rsi_inventory 的库存证据；需要当前 WB 价格时再依据 get_daily_wb。")
            appendLine("规划 WBCCU 时必须分开说明：新增现金、有效总成本、当前可完成性、会消耗哪些已有券、哪些节点需买/需等/未确认。不要把已有券成本误算成新增现金。")
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
        recentToolEvidence(history)?.let { messages += DeepSeekMessage("system", sanitize(it)) }
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
        loopMessages: List<DeepSeekMessage>,
    ): List<DeepSeekMessage> {
        val system = buildString {
            appendLine("你是 ${profile.displayName} (${profile.codename})。")
            appendLine(profile.roleDescription)
            appendLine("人设：${profile.persona.joinToString("；")}")
            appendLine("你不能编造工具结果。")
            appendLine("你要先判断用户是在闲聊、问能力、表达偏好，还是需要 App 数据。能直接回答的闲聊、解释和追问就直接回答；需要实时资料、账号数据、订单、交易、签到、市场价格或本地资料证据时，再调用最合适的工具。")
            appendLine("不要为了显得会查而调用工具；也不要在需要工具证据的问题上凭空回答。")
            appendLine()
            appendLine("重要规则：")
            appendLine("- 工具是你的能力，不是固定脚本；先理解用户意图，再选择是否调用。")
            appendLine("- 遇到出售、求购、挂单、创建订单、发布订单等请求时，调用 draft_scm_order 生成订单草稿。")
            appendLine("- 遇到修改、编辑、上架、下架、删除、补数量等“我的订单/我的挂单”管理请求时，调用 manage_my_order；缺订单编号时先查询并追问订单编号。")
            appendLine("- 订单请求里的“品质850”“质量 900”不是物品名的一部分，调用 draft_scm_order 时必须拆成 quality 参数。")
            AgentToolIntents.promptHints.forEach { appendLine("- $it") }
            appendLine("- 回答市场挂单结果时必须保留工具结果里的卖家/买家数量、卖家/买家昵称、各自价格、数量和地点；不要只说哪里有卖。")
            appendLine("- 市场列表意图：用户没有指定具体物品，只是在问当前 SCM 市场有哪些订单、有什么货、有什么卖单、在售列表、卖家挂了什么等出售列表时，调用 search_market，arguments 必须包含 query=\"\" 和 side=\"sell\"。")
            appendLine("- 市场列表意图：用户没有指定具体物品，只是在问当前 SCM 市场有哪些收单、求购列表、有人收什么、买家要什么等求购列表时，调用 search_market，arguments 必须包含 query=\"\" 和 side=\"buy\"。")
            appendLine("- 市场追问意图：用户在市场查询后追问“谁卖”“谁收”“卖家是谁”“在哪交易”“哪个订单”等卖家、买家、地点或订单详情时，先从最近一条市场工具结果/历史回答中回答；如果历史里缺少该字段，必须复用最近的市场关键词调用 search_market 补查。")
            appendLine("- 市场问题以 search_market 结果为准；如果 search_market 未命中，就直接告诉用户市场暂无对应出售/求购挂单，不要继续调用 search_scm_item、search_local_index、search_mining 等不相关工具。")
            appendLine("- 不允许直接创建订单，只能创建订单草稿。")
            appendLine("- 订单提交必须等待用户确认。")
            appendLine("- WBCCU/CCU 规划意图：用户问我的库存、我的 WB、我的 CCU、升级路线、最省钱、最少新增现金时，先调用 get_rsi_inventory；需要当前 Warbond 折扣/目标船价格时，再调用 get_daily_wb。")
            appendLine("- WBCCU 规划回答必须区分“新增现金”和“有效总成本”，标注当前可完成、需买、需等、未确认，并列出会消耗的关键 CCU。")
            appendLine("- 当工具结果不足以回答问题时，可以继续调用另一个工具。")
            appendLine("- 收到工具结果后，如果已经足以回答用户问题，就直接回答；不要用相同参数重复调用同一个工具。")
            appendLine("- 回答要保留清晰格式：不同板块之间留空行，列表逐行输出，不要把所有文字挤成一段。")
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
        recentToolEvidence(history)?.let { messages += DeepSeekMessage("system", sanitize(it)) }
        messages += DeepSeekMessage("user", sanitize(userText))
        loopMessages.forEach {
            messages += DeepSeekMessage(
                role = it.role,
                content = sanitize(it.content),
                toolCalls = it.toolCalls,
                toolCallId = it.toolCallId,
            )
        }
        return messages
    }

    private fun recentToolEvidence(history: List<AgentMessage>): String? {
        val summary = history.takeLast(MAX_HISTORY)
            .lastOrNull { it.role == AgentMessageRole.ASSISTANT && it.toolSummary.isNotBlank() }
            ?.toolSummary
            ?.trim()
        if (summary.isNullOrBlank()) return null
        return "最近一次工具证据（供追问参考，可直接引用，不要凭空编造新数据）：\n$summary"
    }

    private fun formatToolResults(results: List<AgentToolResult>): String = buildString {
        val useful = results.filter { AgentResultFormatter.hasUsefulData(it) }
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
        val useful = results.filter { AgentResultFormatter.hasUsefulData(it) }
        val visible = useful.ifEmpty { results.take(3) }
        appendLine("可用查询证据：")
        visible.forEach { result ->
            appendLine("- Skill: ${result.skillId}")
            if (result.summary.isNotBlank()) appendLine("  摘要: ${result.summary}")
            result.facts.filter { it.value.isNotBlank() }.take(8).forEach { fact -> appendLine("  ${fact.label}: ${fact.value}") }
        }
        if (useful.isEmpty()) appendLine("没有可靠命中时，最终回答只需简短说明未命中并询问一个可帮助定位的关键词。")
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
