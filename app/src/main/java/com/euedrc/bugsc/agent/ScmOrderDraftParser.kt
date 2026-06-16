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
    val quality: Int? = null,
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
    private val orderIntentWords = listOf(
        "创建订单",
        "创建一个订单",
        "创建挂单",
        "发布订单",
        "发布挂单",
        "帮我创建",
        "挂单",
        "求购",
        "出售",
        "想卖",
        "想买",
        "我要卖",
        "我要买",
        "我想卖",
        "我想买",
    )
    private val sellWords = listOf("出售", "卖", "售卖")
    private val buyWords = listOf("求购", "收购", "买")
    private val quantityRegex = Regex("""(?:数量\s*)?(\d+)\s*(?:个|件|把|份|台|组)""")
    private val explicitQuantityRegex = Regex("""数量\s*(\d+)""")
    private val priceRegex = Regex("""(?:单价|价格|售价|收购价)?\s*(\d+(?:\.\d+)?)\s*(w|万)?\s*(?:auec|uec|元|块)?""", RegexOption.IGNORE_CASE)
    private val labeledPriceRegex = Regex("""(?:单价|价格|售价|收购价)\s*(\d+(?:\.\d+)?)\s*(w|万)?\s*(?:auec|uec|元|块)?""", RegexOption.IGNORE_CASE)
    private val moneyWithUnitRegex = Regex("""(\d+(?:\.\d+)?)\s*(w|万)?\s*(?:auec|uec|元|块)""", RegexOption.IGNORE_CASE)
    private val locationRegex = Regex("""(?:交易地点|地点|在)\s*(?:是|为|:|：)?\s*([^，,。；;\n]+)""")
    private val bareLocationRegex = Regex("""地点\s*([^，,。；;\n]+)""")
    private val qualityRegex = Regex("""(?:品质|质量|quality|q)\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val punctuation = Regex("""[，,。；;\n]+""")
    private val moneyTextRegex = Regex("""(?:单价|价格|售价|收购价)\s*\d+(?:\.\d+)?\s*(?:w|万)?\s*(?:auec|uec|元|块)?|\d+(?:\.\d+)?\s*(?:w|万)\s*(?:auec|uec)?|\d+(?:\.\d+)?\s*(?:auec|uec|元|块)""", RegexOption.IGNORE_CASE)
    private val orderFillerRegex = Regex("""^(我想|我要|请帮我|帮我)?\s*(创建一个|创建|发布一个|发布)?\s*(一个|一件|一把|订单|挂单)?\s*""")
    private val followUpNoiseRegex = Regex("""(你)?搜一下\s*scm|搜一下|scm|就叫|叫|名称是|名字是|物品是|东西是""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ScmOrderDraftParseResult {
        val raw = text.trim()
        val normalized = AgentAliasNormalizer.normalize(raw)
        val creatorType = when {
            sellWords.any { raw.contains(it) } -> PublishCreatorType.SELL
            buyWords.any { raw.contains(it) } -> PublishCreatorType.BUY
            else -> null
        }
        val isIntent = orderIntentWords.any { raw.contains(it) || normalized.contains(AgentAliasNormalizer.normalize(it)) } ||
            (creatorType != null && (hasPrice(raw) || locationRegex.containsMatchIn(raw) || raw.contains("订单") || raw.contains("挂单")))
        val quantity = explicitQuantityRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: quantityRegex.findAll(raw).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.firstOrNull()
            ?: 1
        val unitPrice = parsePrice(raw)
        val location = cleanLocation(
            (locationRegex.find(raw) ?: bareLocationRegex.find(raw))?.groupValues?.getOrNull(1).orEmpty(),
        )
        val quality = parseQuality(raw)
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
            quality = quality,
        )
    }

    fun mergeFollowUp(pending: ScmOrderDraftParseResult, text: String): ScmOrderDraftParseResult {
        val raw = text.trim()
        val next = parse(raw)
        val missing = pending.missingFields.toSet()
        val looksLikePlainValue = raw.isNotBlank() &&
            next.creatorType == null &&
            next.itemKeyword.isBlank() &&
            next.unitPrice == null &&
            next.locationKeyword.isBlank()
        val inferredLocation = if ("交易地点" in missing && looksLikePlainValue) raw else ""
        val cleanedFollowUp = cleanFollowUpValue(raw)
        val inferredItem = if ("物品" in missing && (looksLikePlainValue || cleanedFollowUp != raw)) cleanedFollowUp else ""
        val inferredPrice = if ("单价" in missing) parsePrice(raw) else null
        return ScmOrderDraftParseResult(
            isOrderIntent = true,
            creatorType = next.creatorType ?: pending.creatorType,
            itemKeyword = next.itemKeyword.ifBlank { inferredItem.ifBlank { pending.itemKeyword } },
            quantity = if (hasQuantity(raw)) next.quantity else pending.quantity,
            unitPrice = next.unitPrice ?: inferredPrice ?: pending.unitPrice,
            locationKeyword = next.locationKeyword.ifBlank { inferredLocation.ifBlank { pending.locationKeyword } },
            status = if (raw.contains("下架") || raw.contains("隐藏")) next.status else pending.status,
            expireTime = when {
                raw.contains("永久") || raw.contains("14天") || raw.contains("十四天") -> next.expireTime
                else -> pending.expireTime
            },
            quality = next.quality ?: pending.quality,
        )
    }

    private fun extractItem(raw: String, creatorType: PublishCreatorType?): String {
        if (creatorType == PublishCreatorType.SELL) {
            Regex("""(?:我有一个|我有一件|我有一把|有一个|有一件|有一把|我有)\s*(.+?)\s*(?:想卖|我要卖|出售|卖)""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::cleanItemText)
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        val marker = when (creatorType) {
            PublishCreatorType.SELL -> sellWords.firstOrNull { raw.contains(it) }
            PublishCreatorType.BUY -> buyWords.firstOrNull { raw.contains(it) }
            null -> null
        } ?: return ""
        val after = raw.substringAfter(marker, "")
        return cleanItemText(after)
    }

    private fun cleanItemText(value: String): String =
        value
            .split(punctuation)
            .firstOrNull()
            .orEmpty()
            .replace(locationRegex, "")
            .replace(bareLocationRegex, "")
            .replace(qualityRegex, "")
            .replace(quantityRegex, "")
            .replace(explicitQuantityRegex, "")
            .replace(moneyTextRegex, "")
            .replace(orderFillerRegex, "")
            .replace(Regex("""^(一个|一件|一把|订单|挂单)\s*"""), "")
            .replace(Regex("""\s+\d+\s*(?:个|件|把|份|台|组).*$"""), "")
            .replace(Regex("""数量\s*\d+.*$"""), "")
            .replace(Regex("""单价\s*\d+(?:\.\d+)?.*$"""), "")
            .replace(Regex("""价格\s*\d+(?:\.\d+)?.*$"""), "")
            .replace(Regex("""地点\s*.*$"""), "")
            .replace(Regex("""想卖.*$"""), "")
            .replace(Regex("""想买.*$"""), "")
            .trim()

    private fun hasQuantity(raw: String): Boolean =
        explicitQuantityRegex.containsMatchIn(raw) || quantityRegex.containsMatchIn(raw)

    private fun hasPrice(raw: String): Boolean =
        labeledPriceRegex.containsMatchIn(raw) || moneyWithUnitRegex.containsMatchIn(raw)

    private fun parsePrice(raw: String): BigDecimal? {
        val match = labeledPriceRegex.find(raw)
            ?: raw.split(punctuation)
                .firstOrNull { it.contains("单价") || it.contains("价格") || it.contains("售价") || it.contains("收购价") }
                ?.let { priceRegex.find(it) }
            ?: moneyWithUnitRegex.find(raw)
        return match?.let(::parseMoneyMatch)
    }

    private fun parseMoneyMatch(match: MatchResult): BigDecimal? {
        val amount = match.groupValues.getOrNull(1)?.toBigDecimalOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2).orEmpty().lowercase()
        return if (unit == "w" || unit == "万") {
            amount.multiply(BigDecimal("10000"))
        } else {
            amount
        }
    }

    private fun parseQuality(raw: String): Int? =
        qualityRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..1000 }

    private fun cleanLocation(value: String): String =
        value.trim()
            .removePrefix("是")
            .removePrefix("在")
            .trim()

    private fun cleanFollowUpValue(raw: String): String =
        raw.replace(followUpNoiseRegex, "")
            .replace(punctuation, "")
            .trim()
}
