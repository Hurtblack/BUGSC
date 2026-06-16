package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.MarketOrder
import com.euedrc.bugsc.market.ScmMarketClient
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.MarketPublishClient
import com.euedrc.bugsc.scm.ScmAuthStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

interface ScmAgentGateway {
    fun request(path: String): JSONObject
    fun searchItems(keyword: String): List<ItemSearchResult>
    fun searchOrders(keyword: String): List<MarketOrder>
    fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> =
        creatorType?.let { type -> searchOrders(keyword).filter { it.creatorType == type } } ?: searchOrders(keyword)
}

class DefaultScmAgentGateway(
    private val publishClient: MarketPublishClient = MarketPublishClient(),
    private val marketClient: ScmMarketClient = ScmMarketClient(),
) : ScmAgentGateway {
    override fun request(path: String): JSONObject =
        JSONObject(ScmAuthStore.api().request("GET", path).body)

    override fun searchItems(keyword: String): List<ItemSearchResult> =
        publishClient.searchItems(keyword)

    override fun searchOrders(keyword: String): List<MarketOrder> =
        marketClient.fetchPage(creatorType = 1, pageNo = 1, pageSize = 5, keyword = keyword).list +
            marketClient.fetchPage(creatorType = 0, pageNo = 1, pageSize = 5, keyword = keyword).list

    override fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> =
        if (creatorType == null) {
            searchOrders(keyword)
        } else {
            marketClient.fetchPage(creatorType = creatorType, pageNo = 1, pageSize = 5, keyword = keyword).list
        }
}

object ScmAgentTools {
    fun create(
        gateway: ScmAgentGateway = DefaultScmAgentGateway(),
        entityIndex: AgentEntityIndex = AgentEntityIndex(),
    ): List<AgentTool> = listOf(
        ScmBlueprintSearchTool(gateway, entityIndex),
        ScmItemSearchTool(gateway, entityIndex),
        ScmMarketOrderSearchTool(gateway, entityIndex),
    )
}

class ScmBlueprintSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_scm_blueprint"
    override val description: String = "SCM 蓝图查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 蓝图未命中")
        val list = ScmSearchTermExpander.expand(term, entityIndex)
            .asSequence()
            .map { candidate ->
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                gateway.request("/product/blueprint/page?pageNo=1&pageSize=5&keyword=$encoded")
                    .optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: JSONArray()
            }
            .firstOrNull { it.length() > 0 }
            ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        if (items.isEmpty()) return noResult(call, "SCM 蓝图未命中")
        val summaries = items.take(3).map { it.blueprintDisplayName() }
        val facts = items.take(3).flatMap { item ->
            listOfNotNull(
                AgentFact("蓝图", item.blueprintDisplayName()),
                item.optString("categoryName").takeIf(String::isNotBlank)?.let { AgentFact("分类", it) },
                item.optInt("craftTimeSeconds").takeIf { it > 0 }?.let { AgentFact("制作时间", "${it}s") },
                item.optInt("rewardMissionCount").takeIf { it > 0 }?.let { AgentFact("来源任务数", it.toString()) },
            )
        }
        return AgentToolResult(
            call = call,
            summary = summaries.joinToString("\n"),
            facts = facts,
            sources = listOf(AgentSource("SCM 蓝图 API", "remote")),
            confidence = 0.78f,
        )
    }
}

class ScmItemSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_scm_item"
    override val description: String = "SCM 物品查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 物品未命中")
        val items = ScmSearchTermExpander.expand(term, entityIndex)
            .asSequence()
            .map { gateway.searchItems(it) }
            .firstOrNull { it.isNotEmpty() }
            ?.take(5)
            .orEmpty()
        if (items.isEmpty()) return noResult(call, "SCM 物品未命中")
        return AgentToolResult(
            call = call,
            summary = items.take(3).joinToString("\n") { it.displayName() },
            facts = items.take(3).flatMap {
                listOf(
                    AgentFact("物品", it.displayName()),
                    AgentFact("物品ID", it.id.toString()),
                )
            },
            sources = listOf(AgentSource("SCM 物品 API", "remote")),
            confidence = 0.76f,
        )
    }
}

class ScmMarketOrderSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_market"
    override val description: String = "SCM 市场订单查询"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("query", "可选：要查询的物品名或关键词；为空时返回当前市场订单列表", required = false),
        AgentToolParameter("side", "可选：sell 查询出售挂单；buy 查询求购挂单；不确定时留空", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["query"].orEmpty().ifBlank { call.args["term"].orEmpty() }.trim()
        val creatorType = when (call.args["side"].orEmpty().trim().lowercase()) {
            "sell", "出售", "selling" -> 1
            "buy", "求购", "buying" -> 0
            else -> null
        }
        val orders = if (term.isBlank()) {
            gateway.searchOrders("", creatorType)
                .filter { it.remainingQuantity > 0 }
                .sortedWith(compareBy<MarketOrder> { it.creatorType }.thenBy { it.unitPrice })
                .take(5)
        } else {
            ScmSearchTermExpander.expand(term, entityIndex)
                .asSequence()
                .map { candidate ->
                    gateway.searchOrders(candidate, creatorType)
                        .filter { it.remainingQuantity > 0 }
                        .sortedBy { it.unitPrice }
                        .take(5)
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        if (orders.isEmpty()) {
            val target = term.ifBlank {
                when (creatorType) {
                    1 -> "当前出售挂单"
                    0 -> "当前求购挂单"
                    else -> "当前市场订单"
                }
            }
            return noResult(call, "没有查到“$target”的 SCM 市场挂单。")
        }
        return AgentToolResult(
            call = call,
            summary = orders.take(3).joinToString("\n") {
                buildString {
                    append(it.itemName)
                    append("，")
                    append(if (it.creatorType == 1) "出售" else "求购")
                    append(" ")
                    append(formatPrice(it.unitPrice))
                    append(" aUEC ×")
                    append(it.remainingQuantity)
                    it.nickname.takeIf(String::isNotBlank)?.let { seller -> append("，卖家 ").append(seller) }
                    it.locationName.takeIf(String::isNotBlank)?.let { location -> append("，地点 ").append(location) }
                }
            },
            facts = orders.take(3).flatMap {
                listOfNotNull(
                    AgentFact("订单", it.orderNumber),
                    AgentFact("物品", it.itemName),
                    AgentFact("单价", "${formatPrice(it.unitPrice)} aUEC"),
                    AgentFact("数量", it.remainingQuantity.toString()),
                    it.nickname.takeIf(String::isNotBlank)?.let { seller -> AgentFact("卖家", seller) },
                    it.locationName.takeIf(String::isNotBlank)?.let { location -> AgentFact("地点", location) },
                )
            },
            sources = listOf(AgentSource("SCM 市场 API", "remote")),
            confidence = 0.72f,
        )
    }
}

private fun noResult(call: AgentToolCall, summary: String): AgentToolResult =
    AgentToolResult(
        call = call,
        summary = summary,
        facts = emptyList(),
        sources = listOf(AgentSource(summary.substringBefore("未命中"), "remote")),
        confidence = 0f,
    )

object ScmSearchTermExpander {
    private val noise = Regex(
        """(请问|帮我|查一下|查询|查|搜一下|搜|就叫|叫|名称是|名字是|物品是|东西是|你|scm|市场|订单|挂单)""",
        RegexOption.IGNORE_CASE,
    )

    fun expand(term: String, entityIndex: AgentEntityIndex): List<String> {
        val cleaned = clean(term)
        val compact = AgentAliasNormalizer.compact(cleaned)
        val candidates = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.length >= 2 }?.let { candidates += it }
        }
        add(cleaned)
        add(term)
        if (compact.length >= 2) {
            entityIndex.entries.forEach { entity ->
                val values = listOf(entity.displayName, entity.value) + entity.aliases
                if (values.any { fuzzyContains(it, compact) }) {
                    add(entity.displayName)
                    add(entity.value)
                    entity.aliases.forEach(::add)
                }
            }
        }
        return candidates.toList()
    }

    fun clean(term: String): String =
        term.replace(noise, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun fuzzyContains(value: String, compactTerm: String): Boolean {
        val compactValue = AgentAliasNormalizer.compact(value)
        return compactValue.length >= 2 &&
            (compactValue.contains(compactTerm) || compactTerm.contains(compactValue))
    }
}

private fun JSONObject.blueprintDisplayName(): String {
    val cn = optString("blueprintNameCn").trim()
    val en = optString("blueprintName").trim()
    return when {
        cn.isNotBlank() && en.isNotBlank() && cn != en -> "$cn / $en"
        cn.isNotBlank() -> cn
        else -> en
    }
}

private fun formatPrice(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
