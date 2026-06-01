package com.euedrc.bugsc

import java.text.SimpleDateFormat
import java.util.Locale

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
    val canUpgrade: Boolean
)

class InventoryParseException(message: String) : Exception(message)

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
            val dateText = firstGroup(
                row,
                Regex("""<[^>]*class=["'][^"']*\bdate-col\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""")
            ).orEmpty().substringAfter("Created:", "").cleanText()
            val contains = titleValues(row).joinToString("#")
            val imageUrl = imageUrl(row)

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
                canUpgrade = row.contains("js-apply-upgrade")
            )
        }
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
