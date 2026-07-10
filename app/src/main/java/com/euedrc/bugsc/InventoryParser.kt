package com.euedrc.bugsc

import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class InventoryItem(
    val id: String,
    val name: String,
    val priceCents: Int,
    val currentPriceCents: Int,
    val status: String,
    val date: String,
    val insurance: String,
    val contains: String,
    val imageUrl: String,
    val page: Int,
    val canGift: Boolean,
    val canReclaim: Boolean,
    val canUpgrade: Boolean,
    val subItems: List<InventorySubItem> = emptyList(),
    val upgradeData: String = "",
    val ccuInfo: InventoryCcuInfo? = null
)

data class InventorySubItem(
    val title: String,
    val kind: String,
    val subtitle: String,
    val imageUrl: String
)

data class InventoryCcuInfo(
    val fromShipName: String,
    val toShipName: String,
    val fromShipId: Int? = null,
    val toShipId: Int? = null
)

data class InventoryShipPrice(
    val id: Int?,
    val name: String,
    val priceCents: Int
)

data class InventoryCcuPriceSummary(
    val fromShipName: String,
    val toShipName: String,
    val fromShipPriceCents: Int,
    val toShipPriceCents: Int,
    val standardUpgradeValueCents: Int,
    val paidValueCents: Int,
    val savingCents: Int
)

class InventoryParseException(message: String) : Exception(message)

object InventoryCcuPriceCalculator {
    fun summarize(
        item: InventoryItem,
        ccuInfo: InventoryCcuInfo,
        shipPrices: Map<String, Int>
    ): InventoryCcuPriceSummary? {
        return summarize(
            item = item,
            ccuInfo = ccuInfo,
            shipPrices = shipPrices.map { (name, price) -> InventoryShipPrice(null, name, price) }
        )
    }

    fun summarize(
        item: InventoryItem,
        ccuInfo: InventoryCcuInfo,
        shipPrices: List<InventoryShipPrice>
    ): InventoryCcuPriceSummary? {
        val priceById = shipPrices.mapNotNull { price -> price.id?.let { it to price } }.toMap()
        val priceByExactName = shipPrices.associate { exactShipNameKey(it.name) to it.priceCents }
        val priceByName = shipPrices
            .mapNotNull { price ->
                normalizeShipName(price.name).takeIf { it.isNotBlank() }?.let { it to price.priceCents }
            }
            .toMap()
        val fromPrice = resolvePrice(ccuInfo.fromShipName, ccuInfo.fromShipId, priceById, priceByExactName, priceByName)
            ?: return null
        val toPrice = resolvePrice(ccuInfo.toShipName, ccuInfo.toShipId, priceById, priceByExactName, priceByName)
            ?: return null
        val standardUpgradeValue = (toPrice - fromPrice).coerceAtLeast(0)
        return InventoryCcuPriceSummary(
            fromShipName = ccuInfo.fromShipName,
            toShipName = ccuInfo.toShipName,
            fromShipPriceCents = fromPrice,
            toShipPriceCents = toPrice,
            standardUpgradeValueCents = standardUpgradeValue,
            paidValueCents = item.priceCents,
            savingCents = standardUpgradeValue - item.priceCents
        )
    }

    private fun resolvePrice(
        shipName: String,
        shipId: Int?,
        priceById: Map<Int, InventoryShipPrice>,
        priceByExactName: Map<String, Int>,
        priceByName: Map<String, Int>
    ): Int? {
        val normalizedName = normalizeShipName(shipName)
        val idPrice = shipId?.let { priceById[it] }
        if (idPrice != null && namesAreCompatible(normalizedName, normalizeShipName(idPrice.name))) {
            return idPrice.priceCents
        }
        return priceByExactName[exactShipNameKey(shipName)]
            ?: priceByName[normalizedName]
    }

    internal fun exactShipNameKey(name: String): String {
        return name.lowercase(Locale.US).trim().replace(Regex("""\s+"""), " ")
    }

    private fun namesAreCompatible(expected: String, actual: String): Boolean {
        if (expected.isBlank() || actual.isBlank()) return false
        return expected == actual ||
            actual.startsWith("$expected ") ||
            expected.startsWith("$actual ")
    }

    internal fun normalizeShipName(name: String): String {
        return name.lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""\b(anvil|crusader|aegis|drake|rsi|misc|origin|esperia|gatac|argo|tumbril|greycat|kruger)\b"""), " ")
            .replace(Regex("""\bhercules(?:\s+starlifter)?\s+([acm]2)\b"""), "$1 hercules")
            .replace(Regex("""\bmk\s+i\b"""), "")
            .replace(Regex("""\b(mk|mark)\s+([ivx]+|\d+)\b"""), "$2")
            .replace(Regex("""\bstarlifter\b"""), " ")
            .replace(Regex("""\btank\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }
}

object InventoryShipPriceAliases {
    fun expand(prices: List<InventoryShipPrice>, aliases: Map<String, String>): List<InventoryShipPrice> {
        if (prices.isEmpty() || aliases.isEmpty()) return prices
        val priceByNormalizedName = prices
            .mapNotNull { price ->
                InventoryCcuPriceCalculator.normalizeShipName(price.name)
                    .takeIf { it.isNotBlank() }
                    ?.let { it to price }
            }
            .toMap()
        val aliasPrices = aliases.mapNotNull { (englishName, aliasName) ->
            val price = priceByNormalizedName[InventoryCcuPriceCalculator.normalizeShipName(englishName)]
                ?: return@mapNotNull null
            InventoryShipPrice(null, aliasName, price.priceCents)
        }
        return prices + aliasPrices
    }
}

object InventoryParser {

    fun parseHangarItems(html: String, page: Int): List<InventoryItem> {
        if (!html.contains("js-pledge-id")) {
            throw InventoryParseException("未找到机库内容，登录可能已失效")
        }

        return rowBlocks(html).mapNotNull { row ->
            val id = inputValue(row, "js-pledge-id") ?: return@mapNotNull null
            val value = inputValue(row, "js-pledge-value").orEmpty()
            val name = inputValue(row, "js-pledge-name").orEmpty()
                .substringBefore(" Contains ")
                .trim()
            val status = firstGroup(
                row,
                Regex("""<[^>]*class=["'][^"']*\bavailability\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""")
            ).orEmpty().cleanText()
            val dateText = extractCreatedDate(row)
            val contains = titleValues(row).joinToString("#")
            val imageUrl = imageUrl(row)
            val upgradeData = inputValue(row, "js-upgrade-data").orEmpty()
            val ccuInfo = parseCcuInfo(upgradeData) ?: parseCcuInfoFromName(name)

            InventoryItem(
                id = id,
                name = name,
                priceCents = priceStringToCents(value),
                currentPriceCents = priceStringToCents(value),
                status = status,
                date = convertDate(dateText),
                insurance = parseInsurance(contains),
                contains = contains,
                imageUrl = imageUrl,
                page = page,
                canGift = row.contains("js-gift"),
                canReclaim = row.contains("js-reclaim"),
                canUpgrade = row.contains("js-apply-upgrade") || upgradeData.isNotBlank(),
                subItems = subItems(row),
                upgradeData = upgradeData,
                ccuInfo = ccuInfo
            )
        }
    }

    private fun extractCreatedDate(row: String): String {
        val rawDateCol = firstGroup(
            row,
            Regex("""<[^>]*class=["'][^"']*\bdate-col\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""")
        ).orEmpty().cleanText()
        val fromDateCol = rawDateCol.substringAfter("Created:", "").trim()
        if (dateLooksParseable(fromDateCol)) return fromDateCol

        val wholeText = row.cleanText()
        return Regex("""(?:Created|Date)\s*:\s*([A-Za-z]+ \d{1,2}, \d{4})""", RegexOption.IGNORE_CASE)
            .find(wholeText)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun dateLooksParseable(value: String): Boolean {
        return Regex("""^[A-Za-z]+ \d{1,2}, \d{4}$""").matches(value)
    }

    private fun rowBlocks(html: String): List<String> {
        val starts = Regex("""<[A-Za-z][A-Za-z0-9:-]*[^>]*class=["'][^"']*\brow\b[^"']*["'][^>]*>""")
            .findAll(html)
            .map { it.range.first }
            .toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: html.length
            html.substring(start, end)
        }
    }

    private fun inputValue(html: String, className: String): String? {
        val input = firstGroup(
            html,
            Regex("""<input[^>]*class=["'][^"']*\b${Regex.escape(className)}\b[^"']*["'][^>]*>""")
        ) ?: Regex("""<input[^>]*class=["'][^"']*\b${Regex.escape(className)}\b[^"']*["'][^>]*>""")
            .find(html)
            ?.value
        return input?.let {
            firstGroup(it, Regex("""\bvalue=["']([^"']*)["']"""))?.decodeHtml()
        }
    }

    private fun titleValues(html: String): List<String> {
        return Regex("""<[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""")
            .findAll(html)
            .map { it.groupValues[1].cleanText() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun subItems(row: String): List<InventorySubItem> {
        val areaStart = Regex("""<[A-Za-z][A-Za-z0-9:-]*[^>]*class=["'][^"']*\bwith-images\b[^"']*["'][^>]*>""")
            .find(row)
            ?.range
            ?.first
            ?: return emptyList()
        val area = row.substring(areaStart)
        return itemBlocks(area).mapNotNull { item ->
            val title = firstClassText(item, "title")
            val image = imageUrl(item)
            if (title.isBlank() || image.isBlank()) return@mapNotNull null
            InventorySubItem(
                title = title,
                kind = firstClassText(item, "kind"),
                subtitle = firstClassText(item, "liner"),
                imageUrl = image
            )
        }
    }

    private fun itemBlocks(html: String): List<String> {
        val starts = Regex("""<[A-Za-z][A-Za-z0-9:-]*[^>]*class=["'][^"']*\bitem\b[^"']*["'][^>]*>""")
            .findAll(html)
            .map { it.range.first }
            .toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: html.length
            html.substring(start, end)
        }
    }

    private fun parseCcuInfo(raw: String): InventoryCcuInfo? {
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val fromObj = firstObject(json.optJSONArray("match_items"))
            val toObj = firstObject(json.optJSONArray("target_items"))
            val fromName = nestedName(json, "fromShip")
                ?: nestedName(json, "from_ship")
                ?: fromObj?.optString("name")?.takeIf { it.isNotBlank() }
            val toName = nestedName(json, "toShip")
                ?: nestedName(json, "to_ship")
                ?: toObj?.optString("name")?.takeIf { it.isNotBlank() }
            val fromId = nestedId(json, "fromShip") ?: nestedId(json, "from_ship") ?: fromObj?.optInt("id")?.takeIf { it > 0 }
            val toId = nestedId(json, "toShip") ?: nestedId(json, "to_ship") ?: toObj?.optInt("id")?.takeIf { it > 0 }
            if (fromName.isNullOrBlank() || toName.isNullOrBlank()) {
                null
            } else {
                InventoryCcuInfo(fromName, toName, fromId, toId)
            }
        }.getOrNull()
    }

    private fun parseCcuInfoFromName(name: String): InventoryCcuInfo? {
        val match = Regex("""(?i)\bupgrade\s*-\s*(.+?)\s+to\s+(.+?)(?:\s+(?:standard|warbond)\s+edition)?$""")
            .find(name)
            ?: return null
        val fromName = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val toName = match.groupValues.getOrNull(2)?.trim().orEmpty()
        return if (fromName.isBlank() || toName.isBlank()) null else InventoryCcuInfo(fromName, toName)
    }

    private fun nestedName(json: JSONObject, key: String): String? {
        return json.optJSONObject(key)
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("${key}Name").takeIf { it.isNotBlank() }
    }

    private fun nestedId(json: JSONObject, key: String): Int? {
        return json.optJSONObject(key)?.optInt("id")?.takeIf { it > 0 }
            ?: json.optInt("${key}Id").takeIf { it > 0 }
    }

    private fun firstObject(items: JSONArray?): JSONObject? {
        if (items == null || items.length() == 0) return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            if (item != null) return item
        }
        return null
    }

    private fun firstClassText(html: String, className: String): String {
        return firstGroup(
            html,
            Regex("""<[^>]*class=["'][^"']*\b${Regex.escape(className)}\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""")
        ).orEmpty().cleanText()
    }

    private fun imageUrl(html: String): String {
        val imageTag = Regex("""<[A-Za-z][A-Za-z0-9:-]*[^>]*class=["'][^"']*\bimage\b[^"']*["'][^>]*>""")
            .find(html)
            ?.value
        if (imageTag == null) {
            return backgroundImageUrl(html)
                ?: mediaPathUrl(html)
                ?: ""
        }
        val style = firstGroup(imageTag, Regex("""\bstyle="([^"]*)""""))
            ?: firstGroup(imageTag, Regex("""\bstyle='([^']*)'"""))
        if (!style.isNullOrBlank()) {
            val styleUrl = normalizeImageUrl(style)
            if (styleUrl.isNotBlank()) return styleUrl
        }

        return listOf("src", "data-src", "data-original", "data-lazy")
            .firstNotNullOfOrNull { attr ->
                firstGroup(imageTag, Regex("""\b${Regex.escape(attr)}="([^"]*)""""))
                    ?: firstGroup(imageTag, Regex("""\b${Regex.escape(attr)}='([^']*)'"""))
            }
            ?.let { normalizePlainUrl(it) }
            ?: backgroundImageUrl(html)
            ?: mediaPathUrl(html)
            ?: ""
    }

    private fun backgroundImageUrl(html: String): String? {
        return Regex("""background-image\s*:\s*url\(([^)]*)\)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('\'', '"', ' ')
            ?.let { normalizePlainUrl(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun mediaPathUrl(html: String): String? {
        return Regex("""["']((?:https://robertsspaceindustries\.com)?/(?:media|rsi/static|media/)[^"']+\.(?:jpg|jpeg|png|webp))["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizePlainUrl(it) }
    }

    private fun priceStringToCents(price: String): Int {
        if (price.contains("UEC", ignoreCase = true)) return 0
        val cleaned = price.replace("$", "")
            .replace("USD", "")
            .replace(",", "")
            .trim()
        return (cleaned.toDoubleOrNull()?.times(100))?.toInt() ?: 0
    }

    private fun normalizeImageUrl(style: String): String {
        var url = style.substringAfter("url(", "")
            .substringBefore(")", "")
            .trim('\'', '"', ' ')
        return normalizePlainUrl(url)
    }

    private fun normalizePlainUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("/")) {
            url = "https://robertsspaceindustries.com$url"
        }
        return url
    }

    private fun convertDate(date: String): String {
        if (date.isBlank()) return ""
        return try {
            val original = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
            val target = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
            target.format(original.parse(date)!!)
        } catch (e: Exception) {
            date
        }
    }

    private fun parseInsurance(contains: String): String {
        var months = 0
        contains.split("#").forEach { raw ->
            val item = raw.replace("-", " ").trim()
            if (!item.contains("Insurance", ignoreCase = true)) return@forEach
            if (item.contains("Lifetime", ignoreCase = true)) return "LTI"
            val number = Regex("""\b(\d+)\b""").find(item)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            val itemMonths = if (item.contains("Year", ignoreCase = true)) number * 12 else number
            months = maxOf(months, itemMonths)
        }
        if (months == 0) return ""
        return if (months % 12 == 0) "${months / 12}Y" else "${months}M"
    }

    private fun firstGroup(text: String, regex: Regex): String? = regex.find(text)?.groupValues?.getOrNull(1)

    private fun String.cleanText(): String = replace(Regex("""<[^>]+>"""), "")
        .decodeHtml()
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeHtml(): String = replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
