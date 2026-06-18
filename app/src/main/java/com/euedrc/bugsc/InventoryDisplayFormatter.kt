package com.euedrc.bugsc

import java.util.Locale

data class InventoryDisplay(
    val title: String,
    val subtitle: String,
    val detail: String,
    val tags: List<String>
)

object InventoryDisplayFormatter {

    fun format(item: InventoryItem, shipAliases: Map<String, String>): InventoryDisplay {
        val upgrade = parseUpgrade(item.name)
        val kind = kindLabel(item, upgrade)
        val sale = saleLabel(item.name, item.contains)
        val title = if (upgrade != null) {
            "${translateShip(upgrade.from, shipAliases)} -> ${translateShip(upgrade.to, shipAliases)}"
        } else {
            translateInventoryName(item.name, shipAliases)
        }
        val subtitle = when {
            upgrade != null -> "${upgrade.from} -> ${upgrade.to}"
            title != item.name -> item.name
            else -> ""
        }
        val detail = detailText(item, shipAliases, upgrade)
        val tags = buildList {
            add(kind)
            sale?.let { add(it) }
            if (item.priceCents > 0) add("价格 ${money(item.priceCents)}")
            if (item.insurance.isNotBlank()) add(item.insurance)
            add("P${item.page}")
            if (item.canGift) add("礼物")
            if (item.canReclaim) add("可融")
            if (item.canUpgrade && upgrade == null) add("可升级")
        }
        return InventoryDisplay(title = title, subtitle = subtitle, detail = detail, tags = tags)
    }

    private fun parseUpgrade(name: String): UpgradeInfo? {
        val match = Regex(
            """^\s*Upgrade\s*-\s*(.+?)\s+to\s+(.+?)(?:\s+(Warbond|Standard)\s+Edition)?\s*$""",
            RegexOption.IGNORE_CASE
        ).find(name) ?: return null
        val from = match.groupValues[1].cleanShipName()
        val to = match.groupValues[2].cleanShipName()
        if (from.isBlank() || to.isBlank()) return null
        return UpgradeInfo(from, to)
    }

    private fun kindLabel(item: InventoryItem, upgrade: UpgradeInfo?): String {
        val text = "${item.name} ${item.contains}"
        return when {
            upgrade != null -> "CCU"
            text.containsAny("paint", "livery", "skin", "paint pack") -> "皮肤"
            text.containsAny("starter pack", "package", "game package", "star citizen digital download") -> "包"
            item.canUpgrade -> "整船/包"
            else -> "物品"
        }
    }

    private fun saleLabel(name: String, contains: String): String? {
        val text = "$name $contains"
        return when {
            text.contains("Warbond", ignoreCase = true) -> "WB"
            text.contains("Standard Edition", ignoreCase = true) -> "标准"
            else -> null
        }
    }

    private fun translateInventoryName(name: String, shipAliases: Map<String, String>): String {
        val cleaned = name
            .removePrefix("Standalone Ship - ")
            .removeSuffix(" Warbond Edition")
            .removeSuffix(" Standard Edition")
            .cleanShipName()
        val translated = translateShip(cleaned, shipAliases)
        return if (translated != cleaned) translated else name
    }

    private fun detailText(
        item: InventoryItem,
        shipAliases: Map<String, String>,
        upgrade: UpgradeInfo?
    ): String {
        if (upgrade != null) return ""
        val parts = item.contains.split("#")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.equals(item.name, ignoreCase = true) }
            .filterNot { it.contains("Insurance", ignoreCase = true) }
            .distinct()
            .take(4)
            .map { translateInventoryName(it, shipAliases) }
        return if (parts.isEmpty()) "" else "包含：${parts.joinToString("、")}"
    }

    private fun translateShip(name: String, shipAliases: Map<String, String>): String {
        val cleaned = name.cleanShipName()
        shipAliases[cleaned]?.let { return it }
        val compact = cleaned.normalizeKey()
        shipAliases.entries.firstOrNull { it.key.cleanShipName().normalizeKey() == compact }?.let {
            return it.value
        }
        shipAliases.entries.firstOrNull { (key, _) ->
            val normalizedKey = key.cleanShipName().normalizeKey()
            normalizedKey.startsWith(compact)
        }?.let { return it.value }
        return cleaned
    }

    private fun money(cents: Int): String = "$${"%.2f".format(Locale.US, cents / 100.0)}"

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    private fun String.cleanShipName(): String = trim()
        .removeSuffix(" Warbond Edition")
        .removeSuffix(" Standard Edition")
        .removeSuffix(" Edition")
        .trim()

    private fun String.normalizeKey(): String =
        lowercase(Locale.US).replace(Regex("""[^a-z0-9]"""), "")

    private data class UpgradeInfo(val from: String, val to: String)
}
