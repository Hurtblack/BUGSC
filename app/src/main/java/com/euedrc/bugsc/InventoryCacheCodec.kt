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
                item.canUpgrade.toString(),
                encodeSubItems(item.subItems),
                item.upgradeData,
                item.ccuInfo?.fromShipName.orEmpty(),
                item.ccuInfo?.toShipName.orEmpty(),
                item.ccuInfo?.fromShipId?.toString().orEmpty(),
                item.ccuInfo?.toShipId?.toString().orEmpty()
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
                        canUpgrade = parts[12].toBooleanStrictOrNull() ?: false,
                        subItems = parts.getOrNull(13)?.let { decodeSubItems(it) } ?: emptyList(),
                        upgradeData = parts.getOrNull(14).orEmpty(),
                        ccuInfo = decodeCcuInfo(
                            fromShipName = parts.getOrNull(15),
                            toShipName = parts.getOrNull(16),
                            fromShipId = parts.getOrNull(17),
                            toShipId = parts.getOrNull(18)
                        )
                    )
                }.getOrNull()
            }
            .toList()
    }

    private fun encodeSubItems(items: List<InventorySubItem>): String {
        return items.joinToString(SUB_ITEM_SEPARATOR) { item ->
            listOf(
                item.title,
                item.kind,
                item.subtitle,
                item.imageUrl
            ).joinToString(SUB_FIELD_SEPARATOR)
        }
    }

    private fun decodeSubItems(raw: String): List<InventorySubItem> {
        if (raw.isBlank()) return emptyList()
        return raw.split(SUB_ITEM_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(SUB_FIELD_SEPARATOR)
                if (parts.size < SUB_FIELD_COUNT) return@mapNotNull null
                InventorySubItem(
                    title = parts[0],
                    kind = parts[1],
                    subtitle = parts[2],
                    imageUrl = parts[3]
                )
            }
    }

    private fun decodeCcuInfo(
        fromShipName: String?,
        toShipName: String?,
        fromShipId: String?,
        toShipId: String?
    ): InventoryCcuInfo? {
        val from = fromShipName.orEmpty()
        val to = toShipName.orEmpty()
        return if (from.isBlank() || to.isBlank()) {
            null
        } else {
            InventoryCcuInfo(from, to, fromShipId?.toIntOrNull(), toShipId?.toIntOrNull())
        }
    }

    private fun String.cacheEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }

    private fun String.cacheDecode(): String {
        return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    }

    private const val FIELD_COUNT = 13
    private const val SUB_FIELD_COUNT = 4
    private const val SUB_ITEM_SEPARATOR = "\u001E"
    private const val SUB_FIELD_SEPARATOR = "\u001F"
}
