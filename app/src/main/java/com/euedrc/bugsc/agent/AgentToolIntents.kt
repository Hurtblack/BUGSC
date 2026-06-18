package com.euedrc.bugsc.agent

/**
 * 工具意图的单一数据源。
 *
 * "哪种说法 → 调哪个工具、带什么参数" 这套映射以前在两个地方各手写一份：
 * [AgentPromptBuilder] 给模型的 "常见判断" 引导、以及 [AgentRuntime] 在模型未调用工具时的
 * 确定性兜底。两份会随时间漂移、且无法单点测试。这里把它集中定义一次，prompt 与兜底都从这里派生。
 */
data class ToolIntentRule(
    /** 目标工具名。 */
    val tool: String,
    /** 渲染进系统提示词的 "常见判断" 引导句（不含前缀 "- "）。 */
    val hint: String,
    /** 命中关键词的判定；compact 后的文本。 */
    val matches: (String) -> Boolean,
    /** 命中后解析出的工具参数。 */
    val argResolver: (String) -> Map<String, String> = { emptyMap() },
    /**
     * 模型未调用工具时是否用作确定性兜底。非空表示参与兜底，值即兜底产生的 [AgentToolCall.reason]；
     * 为 null 表示只用于引导模型（例如 search_market 的买卖方向不做兜底）。
     */
    val deterministicReason: String? = null,
) {
    val isDeterministic: Boolean get() = deterministicReason != null

    fun deterministicCall(compactText: String): AgentToolCall? {
        val reason = deterministicReason ?: return null
        if (!matches(compactText)) return null
        return AgentToolCall(tool, argResolver(compactText), reason)
    }
}

object AgentToolIntents {

    /** 这些 "创建/挂单" 类请求应交给 draft_scm_order，不能被 "我的订单" 兜底误捕。 */
    private val createOrDraftPhrases = listOf("创建", "发布订单", "发布挂单", "挂一个", "帮我挂", "我要卖", "我要买")

    private val signIn = ToolIntentRule(
        tool = "scm_sign_in",
        hint = "常见判断：用户要求“签到”“帮我签到”时，通常调用 scm_sign_in 且 action=sign；用户询问“签到了吗”“签到状态”“连续签到”时，通常调用 scm_sign_in 且 action=status。",
        matches = { it.contains("签到") },
        argResolver = { value ->
            val action = if (
                value.contains("了吗") || value.contains("状态") || value.contains("信息") ||
                value.contains("连续") || value.contains("累计")
            ) "status" else "sign"
            mapOf("action" to action)
        },
        deterministicReason = "deterministic_scm_sign_in",
    )

    private val myMarketActivity = ToolIntentRule(
        tool = "list_my_market_activity",
        hint = "常见判断：用户询问“我的交易”“交易记录”“我买的单”“我卖出的单”时，通常调用 list_my_market_activity，同时返回我的挂单和我的交易记录。",
        matches = { value ->
            has(value, "我的交易", "查我的交易", "交易记录", "我买的单", "我买过", "我卖出的单") ||
                ((value.contains("我买") || value.contains("我卖出")) && value.contains("单"))
        },
        deterministicReason = "deterministic_my_market_activity",
    )

    private val manageMyOrder = ToolIntentRule(
        tool = "manage_my_order",
        hint = "常见判断：用户要求“下架/上架/删除/编辑/修改/补数量”自己的 SCM 订单时，通常调用 manage_my_order；删除必须带明确确认，否则先请求确认。",
        matches = { false },
    )

    private val myOrders = ToolIntentRule(
        tool = "list_my_orders",
        hint = "常见判断：用户询问“我有什么订单”“我的订单”“我的挂单”“我发布了什么”时，通常调用 list_my_orders；如果明确问我的出售/求购挂单，传 side=sell 或 side=buy。",
        matches = { value ->
            has(value, "我有什么订单", "我的订单", "我的挂单", "我有哪些订单", "我有什么挂单", "我发布了什么") ||
                ((value.contains("我的") || value.startsWith("我")) && (value.contains("订单") || value.contains("挂单")))
        },
        argResolver = { value ->
            when {
                value.contains("出售") || value.contains("卖单") -> mapOf("side" to "sell")
                value.contains("求购") || value.contains("收单") || value.contains("买单") -> mapOf("side" to "buy")
                else -> emptyMap()
            }
        },
        deterministicReason = "deterministic_my_orders",
    )

    private val rsiInventory = ToolIntentRule(
        tool = "get_rsi_inventory",
        hint = "常见判断：用户询问“我的 RSI 机库库存”“我的库存”“我有什么 CCU/WB/WBCCU”“CCU 规划”“升级路线规划”时，通常先调用 get_rsi_inventory；如果还需要当前 WB 价格，再继续调用 get_daily_wb。",
        matches = { value ->
            has(value, "rsi机库库存", "机库库存", "我的库存", "我的机库", "我的ccu", "我的wb", "wbccu", "ccu规划", "升级路线", "升级规划") ||
                ((value.contains("我") || value.contains("库存")) && (value.contains("ccu") || value.contains("wb")))
        },
        argResolver = { value ->
            when {
                value.contains("皮肤") || value.contains("paint") || value.contains("skin") -> mapOf("type" to "paint")
                value.contains("ccu") || value.contains("wbccu") -> mapOf("type" to "ccu")
                value.contains("wb") -> mapOf("type" to "wb")
                else -> emptyMap()
            }
        },
        deterministicReason = "deterministic_rsi_inventory",
    )

    private val rsiServerStatus = ToolIntentRule(
        tool = "get_rsi_server_status",
        hint = "常见判断：用户询问“星际公民服务器状态”“RSI 状态”“PU 维护了吗”“停服了吗”时，通常调用 get_rsi_server_status。",
        matches = { value ->
            has(value, "服务器状态", "rsi状态", "状态页", "pu状态", "persistentuniverse状态", "arena状态", "arenacommander状态") ||
                (
                    has(value, "维护", "停服", "停机", "炸服", "离线") &&
                        has(value, "星际", "星际公民", "rsi", "服务器", "pu", "persistentuniverse", "arena", "arenacommander")
                    )
        },
        deterministicReason = "deterministic_rsi_server_status",
    )

    private val marketSell = ToolIntentRule(
        tool = "search_market",
        hint = "常见判断：用户询问“我要买/现在多少钱/哪里买”时，通常调用 search_market 且 side=sell，查询当前出售挂单。",
        matches = { false },
    )

    private val marketBuy = ToolIntentRule(
        tool = "search_market",
        hint = "常见判断：用户询问“我要卖/能卖多少钱/有人收吗”时，通常调用 search_market 且 side=buy，查询当前求购挂单。",
        matches = { false },
    )

    /** 渲染顺序对齐 prompt：买、卖、我的交易、订单管理、我的订单、签到、RSI 状态、RSI 库存。 */
    val rules: List<ToolIntentRule> =
        listOf(marketSell, marketBuy, myMarketActivity, manageMyOrder, myOrders, signIn, rsiServerStatus, rsiInventory)

    /** 供 [AgentPromptBuilder] 渲染的 "常见判断" 引导句。 */
    val promptHints: List<String> get() = rules.map { it.hint }

    /**
     * 模型未调用工具时的确定性兜底。
     * 顺序与排除规则与原 deterministicToolCall 等价：签到、我的交易优先于 "创建/挂单" 排除；
     * 只有 "我的订单" 会被创建类请求挡住。
     */
    fun deterministicCall(rawText: String): AgentToolCall? {
        val value = AgentAliasNormalizer.compact(rawText)
        signIn.deterministicCall(value)?.let { return it }
        rsiServerStatus.deterministicCall(value)?.let { return it }
        rsiInventory.deterministicCall(value)?.let { return it }
        myMarketActivity.deterministicCall(value)?.let { return it }
        deterministicManageMyOrder(rawText, value)?.let { return it }
        if (has(value, *createOrDraftPhrases.toTypedArray())) return null
        return myOrders.deterministicCall(value)
    }

    private fun has(compactText: String, vararg phrases: String): Boolean =
        phrases.any { compactText.contains(AgentAliasNormalizer.compact(it)) }

    private fun deterministicManageMyOrder(rawText: String, compactText: String): AgentToolCall? {
        val action = when {
            has(compactText, "下架", "隐藏", "暂停") -> "hide"
            has(compactText, "上架", "恢复") -> "show"
            has(compactText, "删除", "删掉", "移除") -> "delete"
            has(compactText, "补数量", "补货") -> "quantity_add"
            has(compactText, "编辑", "修改", "改价", "价格改", "数量改", "改成") -> "edit"
            else -> null
        } ?: return null
        if (!has(compactText, "订单", "挂单")) return null
        val args = linkedMapOf("action" to action)
        extractOrderNumber(rawText)?.let { args["orderNumber"] = it }
        return AgentToolCall("manage_my_order", args, "deterministic_manage_my_order")
    }

    private fun extractOrderNumber(rawText: String): String? {
        val direct = Regex("""(?i)(?:订单|挂单)\s*[:：#]?\s*([A-Z0-9][A-Z0-9_-]{2,})""")
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
        if (!direct.isNullOrBlank()) return direct
        return Regex("""(?i)\b[A-Z]{2,}[A-Z0-9_-]{1,}\b""")
            .find(rawText)
            ?.value
    }
}
