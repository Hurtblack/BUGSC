package com.euedrc.bugsc

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object InventoryCacheCodec {

    fun encodeItems(items: List<InventoryItem>): String {
        return items.joinToString("\n") { item ->
            listOf(
                item.id,
                item.name,
                item.priceCents.toString(),
                item.currentPriceCents.toString(),
                item.status,
                item.date,
                item.insurance,
                item.contains,
                item.imageUrl,
                item.page.toString(),
                item.canGift.toString(),
                item.canReclaim.toString(),
                item.canUpgrade.toString()
            ).joinToString("\t") { it.cacheEncode() }
        }
    }

    fun decodeItems(json: String): List<InventoryItem> {
        if (json.isBlank()) return emptyList()
        return json.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t").map { it.cacheDecode() }
                if (parts.size < FIELD_COUNT) return@mapNotNull null
                runCatching {
                    InventoryItem(
                        id = parts[0],
                        name = parts[1],
                        priceCents = parts[2].toIntOrNull() ?: 0,
                        currentPriceCents = parts[3].toIntOrNull() ?: parts[2].toIntOrNull() ?: 0,
                        status = parts[4],
                        date = parts[5],
                        insurance = parts[6],
                        contains = parts[7],
                        imageUrl = parts[8],
                        page = parts[9].toIntOrNull() ?: 0,
                        canGift = parts[10].toBooleanStrictOrNull() ?: false,
                        canReclaim = parts[11].toBooleanStrictOrNull() ?: false,
                        canUpgrade = parts[12].toBooleanStrictOrNull() ?: false
                    )
                }.getOrNull()
            }
            .toList()
    }

    private fun String.cacheEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }

    private fun String.cacheDecode(): String {
        return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    }

    private const val FIELD_COUNT = 13
}
