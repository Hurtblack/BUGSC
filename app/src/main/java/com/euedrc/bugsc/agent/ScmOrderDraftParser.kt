package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import java.math.BigDecimal

data class ScmOrderDraftParseResult(
    val isOrderIntent: Boolean,
    val creatorType: PublishCreatorType?,
    val itemKeyword: String,
    val quantity: Int,
    val unitPrice: BigDecimal?,
    val locationKeyword: String,
    val status: PublishOrderStatus = PublishOrderStatus.VISIBLE,
    val expireTime: PublishExpireTime = PublishExpireTime.SEVEN_DAYS,
) {
    val missingFields: List<String>
        get() = buildList {
            if (creatorType == null) add("出售或求购")
            if (itemKeyword.isBlank()) add("物品")
            if (quantity <= 0) add("数量")
            if (unitPrice == null || unitPrice <= BigDecimal.ZERO) add("单价")
            if (locationKeyword.isBlank()) add("交易地点")
        }

    val isReadyForResolution: Boolean get() = isOrderIntent && missingFields.isEmpty()
}

object ScmOrderDraftParser {
    private val orderIntentWords = listOf("创建订单", "创建一个订单", "创建挂单", "发布订单", "发布挂单", "帮我创建", "挂单", "求购", "出售")
    private val sellWords = listOf("出售", "卖", "售卖")
    private val buyWords = listOf("求购", "收购", "买")
    private val quantityRegex = Regex("""(?:数量\s*)?(\d+)\s*(?:个|件|把|份|台|组)\b?""")
    private val explicitQuantityRegex = Regex("""数量\s*(\d+)""")
    private val priceRegex = Regex("""(?:单价|价格|售价|收购价)?\s*(\d+(?:\.\d+)?)\s*(?:auec|uec|元|块)?""", RegexOption.IGNORE_CASE)
    private val labeledPriceRegex = Regex("""(?:单价|价格|售价|收购价)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val locationRegex = Regex("""(?:地点|交易地点|在)\s*([^，,。；;\n]+)""")
    private val punctuation = Regex("""[，,。；;\n]+""")

    fun parse(text: String): ScmOrderDraftParseResult {
        val raw = text.trim()
        val normalized = AgentAliasNormalizer.normalize(raw)
        val isIntent = orderIntentWords.any { raw.contains(it) || normalized.contains(AgentAliasNormalizer.normalize(it)) }
        val creatorType = when {
            sellWords.any { raw.contains(it) } -> PublishCreatorType.SELL
            buyWords.any { raw.contains(it) } -> PublishCreatorType.BUY
            else -> null
        }
        val quantity = explicitQuantityRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: quantityRegex.findAll(raw).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.firstOrNull()
            ?: 1
        val unitPrice = labeledPriceRegex.find(raw)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()
            ?: raw.split(punctuation)
                .firstOrNull { it.contains("单价") || it.contains("价格") || it.contains("售价") || it.contains("收购价") }
                ?.let { priceRegex.find(it)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull() }
        val location = locationRegex.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return ScmOrderDraftParseResult(
            isOrderIntent = isIntent,
            creatorType = creatorType,
            itemKeyword = extractItem(raw, creatorType),
            quantity = quantity,
            unitPrice = unitPrice,
            locationKeyword = location,
            status = if (raw.contains("下架") || raw.contains("隐藏")) PublishOrderStatus.HIDDEN else PublishOrderStatus.VISIBLE,
            expireTime = when {
                raw.contains("永久") -> PublishExpireTime.PERMANENT
                raw.contains("14天") || raw.contains("十四天") -> PublishExpireTime.FOURTEEN_DAYS
                else -> PublishExpireTime.SEVEN_DAYS
            },
        )
    }

    private fun extractItem(raw: String, creatorType: PublishCreatorType?): String {
        val marker = when (creatorType) {
            PublishCreatorType.SELL -> sellWords.firstOrNull { raw.contains(it) }
            PublishCreatorType.BUY -> buyWords.firstOrNull { raw.contains(it) }
            null -> null
        } ?: return ""
        val after = raw.substringAfter(marker, "")
        return after
            .split(punctuation)
            .firstOrNull()
            .orEmpty()
            .replace(Regex("""^(一个|一件|一把|订单|挂单)\s*"""), "")
            .replace(Regex("""\s+\d+\s*(?:个|件|把|份|台|组).*$"""), "")
            .replace(Regex("""数量\s*\d+.*$"""), "")
            .replace(Regex("""单价\s*\d+(?:\.\d+)?.*$"""), "")
            .replace(Regex("""价格\s*\d+(?:\.\d+)?.*$"""), "")
            .replace(Regex("""地点\s*.*$"""), "")
            .trim()
    }
}
