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
}

object ScmAgentTools {
    fun create(gateway: ScmAgentGateway = DefaultScmAgentGateway()): List<AgentTool> = listOf(
        ScmBlueprintSearchTool(gateway),
        ScmItemSearchTool(gateway),
        ScmMarketOrderSearchTool(gateway),
    )
}

class ScmBlueprintSearchTool(
    private val gateway: ScmAgentGateway,
) : AgentTool {
    override val name: String = "search_scm_blueprint"
    override val description: String = "SCM 蓝图查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 蓝图未命中")
        val encoded = URLEncoder.encode(term, "UTF-8")
        val root = gateway.request("/product/blueprint/page?pageNo=1&pageSize=5&keyword=$encoded")
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
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
) : AgentTool {
    override val name: String = "search_scm_item"
    override val description: String = "SCM 物品查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 物品未命中")
        val items = gateway.searchItems(term).take(5)
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
) : AgentTool {
    override val name: String = "search_market"
    override val description: String = "SCM 市场订单查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 市场未命中")
        val orders = gateway.searchOrders(term)
            .filter { it.remainingQuantity > 0 }
            .sortedBy { it.unitPrice }
            .take(5)
        if (orders.isEmpty()) return noResult(call, "SCM 市场未命中")
        return AgentToolResult(
            call = call,
            summary = orders.take(3).joinToString("\n") {
                "${it.itemName}，${if (it.creatorType == 1) "出售" else "求购"} ${formatPrice(it.unitPrice)} aUEC ×${it.remainingQuantity}"
            },
            facts = orders.take(3).flatMap {
                listOf(
                    AgentFact("订单", it.orderNumber),
                    AgentFact("物品", it.itemName),
                    AgentFact("单价", "${formatPrice(it.unitPrice)} aUEC"),
                    AgentFact("数量", it.remainingQuantity.toString()),
                    AgentFact("地点", it.locationName),
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
