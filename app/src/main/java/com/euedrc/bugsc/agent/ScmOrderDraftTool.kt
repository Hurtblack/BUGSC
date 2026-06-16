package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import com.euedrc.bugsc.market.transaction.AddressNode

data class ScmOrderDraftToolState(
    var pendingParse: ScmOrderDraftParseResult? = null,
    var resolved: ScmOrderDraftResolution.Resolved? = null,
)

class ScmOrderDraftTool(
    private val state: ScmOrderDraftToolState,
    private val isLoggedIn: () -> Boolean,
    private val itemSearch: (String) -> List<ItemSearchResult>,
    private val addressList: () -> List<AddressNode>,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "draft_scm_order"
    override val description: String = "创建或补全 SCM 出售/求购挂单草稿；只生成草稿，不会提交订单"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter(
            name = "query",
            description = "用户完整的订单创建请求，或对上一个订单草稿追问的补充内容；如果已提供结构化字段，可留空",
            required = false,
        ),
        AgentToolParameter(
            name = "side",
            description = "订单方向：sell 表示出售，buy 表示求购",
            required = false,
        ),
        AgentToolParameter(
            name = "item",
            description = "物品名",
            required = false,
        ),
        AgentToolParameter(
            name = "quantity",
            description = "数量",
            required = false,
        ),
        AgentToolParameter(
            name = "unit_price",
            description = "单价，aUEC",
            required = false,
        ),
        AgentToolParameter(
            name = "quality",
            description = "可选，材料品质，0 到 1000。例如“品质850”传 850",
            required = false,
        ),
        AgentToolParameter(
            name = "location",
            description = "交易地点",
            required = false,
        ),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val query = call.args["query"].orEmpty().ifBlank { call.args["term"].orEmpty() }.trim()
        if (query.isBlank() && !call.args.hasStructuredDraftArgs()) {
            return result(call, "订单草稿缺少用户请求。", confidence = 0f)
        }
        if (!isLoggedIn()) {
            state.resolved = null
            return result(call, "需要先登录 SCM，才能创建订单草稿。", confidence = 0.5f)
        }
        val parsed = structuredParse(call.args)
            ?: state.pendingParse?.let { ScmOrderDraftParser.mergeFollowUp(it, query) }
            ?: ScmOrderDraftParser.parse(query)
        val resolution = ScmOrderDraftResolver(
            itemSearch = { keyword ->
                ScmSearchTermExpander.expand(keyword, entityIndex)
                    .asSequence()
                    .map { itemSearch(it) }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            },
            addressList = addressList,
        ).resolve(parsed)
        return when (resolution) {
            is ScmOrderDraftResolution.NeedMoreInfo -> {
                state.pendingParse = parsed
                state.resolved = null
                result(call, resolution.message, confidence = 0.65f)
            }
            is ScmOrderDraftResolution.Resolved -> {
                state.pendingParse = null
                state.resolved = resolution
                result(
                    call = call,
                    summary = resolution.confirmationMarkdown(),
                    facts = listOf(
                        AgentFact("类型", resolution.parsed.creatorType?.label.orEmpty()),
                        AgentFact("物品", resolution.item.displayName()),
                        AgentFact("数量", resolution.parsed.quantity.toString()),
                        AgentFact("单价", "${resolution.parsed.unitPrice?.cleanText()} aUEC"),
                        resolution.parsed.quality?.let { AgentFact("品质", it.toString()) },
                        AgentFact("地点", resolution.location.name),
                        AgentFact("安全限制", "这里只生成草稿，必须等待用户点击确认后才提交订单。"),
                    ).filterNotNull(),
                    confidence = 0.95f,
                )
            }
        }
    }

    private fun result(
        call: AgentToolCall,
        summary: String,
        facts: List<AgentFact> = emptyList(),
        confidence: Float,
    ): AgentToolResult =
        AgentToolResult(
            call = call,
            summary = summary,
            facts = facts,
            sources = listOf(AgentSource("SCM 订单草稿工具", "local")),
            confidence = confidence,
        )

    private fun structuredParse(args: Map<String, String>): ScmOrderDraftParseResult? {
        if (!args.hasStructuredDraftArgs()) return null
        val side = args["side"].orEmpty().trim().lowercase()
        val itemParts = splitItemAndQuality(args["item"].orEmpty().trim())
        val creatorType = when (side) {
            "sell", "出售", "卖", "selling" -> PublishCreatorType.SELL
            "buy", "求购", "买", "buying" -> PublishCreatorType.BUY
            else -> null
        }
        val quantity = args["quantity"].orEmpty().trim().toIntOrNull() ?: 1
        val unitPrice = args["unit_price"].orEmpty().trim().toBigDecimalOrNull()
        val quality = args["quality"].orEmpty().trim().toIntOrNull()
            ?: itemParts.second
        return ScmOrderDraftParseResult(
            isOrderIntent = true,
            creatorType = creatorType,
            itemKeyword = itemParts.first,
            quantity = quantity,
            unitPrice = unitPrice,
            locationKeyword = args["location"].orEmpty().trim(),
            status = PublishOrderStatus.VISIBLE,
            expireTime = when (args["expire_time"].orEmpty().trim().lowercase()) {
                "permanent", "永久" -> PublishExpireTime.PERMANENT
                "14", "14d", "fourteen_days", "14天", "十四天" -> PublishExpireTime.FOURTEEN_DAYS
                else -> PublishExpireTime.SEVEN_DAYS
            },
            quality = quality?.takeIf { it in 0..1000 },
        )
    }

    private fun Map<String, String>.hasStructuredDraftArgs(): Boolean =
        listOf("side", "item", "quantity", "unit_price", "quality", "location").any { this[it].orEmpty().isNotBlank() }

    private fun splitItemAndQuality(item: String): Pair<String, Int?> {
        val qualityRegex = Regex("""(?:品质|质量|quality|q)\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        val quality = qualityRegex.find(item)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleanItem = item.replace(qualityRegex, "").trim()
        return cleanItem to quality
    }
}
