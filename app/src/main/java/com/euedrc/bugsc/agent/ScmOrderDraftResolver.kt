package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.transaction.AddressNode
import java.math.BigDecimal

sealed class ScmOrderDraftResolution {
    data class Resolved(
        val parsed: ScmOrderDraftParseResult,
        val item: ItemSearchResult,
        val location: AddressNode,
    ) : ScmOrderDraftResolution() {
        fun confirmationMarkdown(): String = buildString {
            appendLine("### 订单草稿")
            appendLine("- 类型：${parsed.creatorType?.label.orEmpty()}")
            appendLine("- 物品：${item.displayName()}")
            appendLine("- 数量：${parsed.quantity}")
            appendLine("- 单价：${parsed.unitPrice?.stripTrailingZeros()?.toPlainString()} aUEC")
            appendLine("- 地点：${location.name}")
            appendLine("- 有效期：${parsed.expireTime.label}")
            appendLine("- 状态：${parsed.status.label}")
            appendLine()
            append("确认后会创建 SCM 挂单。")
        }.trim()
    }

    data class NeedMoreInfo(val message: String) : ScmOrderDraftResolution()
}

class ScmOrderDraftResolver(
    private val itemSearch: (String) -> List<ItemSearchResult>,
    private val addressList: () -> List<AddressNode>,
) {
    fun resolve(parsed: ScmOrderDraftParseResult): ScmOrderDraftResolution {
        if (!parsed.isOrderIntent) {
            return ScmOrderDraftResolution.NeedMoreInfo("这不像创建订单请求。")
        }
        if (!parsed.isReadyForResolution) {
            return ScmOrderDraftResolution.NeedMoreInfo(
                "还缺：${parsed.missingFields.joinToString("、")}。\n可以直接回复缺的内容，比如“死局”或“单价 50000”。",
            )
        }
        val item = itemSearch(parsed.itemKeyword).firstOrNull()
            ?: return ScmOrderDraftResolution.NeedMoreInfo("没有找到物品：${parsed.itemKeyword}。可以换中文名或英文名。")
        val location = findLocation(parsed.locationKeyword, addressList())
            ?: return ScmOrderDraftResolution.NeedMoreInfo("没有找到交易地点：${parsed.locationKeyword}。可以换成完整地点名。")
        return ScmOrderDraftResolution.Resolved(parsed, item, location)
    }

    private fun findLocation(keyword: String, locations: List<AddressNode>): AddressNode? {
        val normalized = AgentAliasNormalizer.compact(keyword)
        if (normalized.isBlank()) return null
        return locations.firstOrNull { AgentAliasNormalizer.compact(it.name) == normalized }
            ?: locations.firstOrNull { AgentAliasNormalizer.compact(it.name).contains(normalized) }
            ?: locations.firstOrNull { normalized.contains(AgentAliasNormalizer.compact(it.name)) }
    }
}

fun ItemSearchResult.displayName(): String =
    when {
        itemName.isNotBlank() && itemNameEn.isNotBlank() && itemName != itemNameEn -> "$itemName / $itemNameEn"
        itemName.isNotBlank() -> itemName
        else -> itemNameEn
    }

fun BigDecimal.cleanText(): String = stripTrailingZeros().toPlainString()
