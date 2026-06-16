package com.euedrc.bugsc.shipfit

object ShipSearchResolver {
    private val punctuation = Regex("""[，。？！?！、,.;；:：()\[\]{}"'“”‘’/\\_-]""")
    private val manufacturerPrefixes = listOf(
        "RSI",
        "Roberts Space Industries",
        "Aegis",
        "Aegis Dynamics",
        "Anvil",
        "Drake",
        "MISC",
        "Origin",
        "Crusader",
        "Argo",
        "Mirai",
        "Esperia",
        "Gatac",
        "Aopoa",
        "Kruger",
        "Consolidated Outland",
        "C.O.",
        "Banu",
        "Vanduul",
        "Tumbril",
        "Greycat",
    )

    fun resolve(input: String, ships: List<ShipCard>): ShipCard? {
        val query = normalize(input)
        if (query.isBlank()) return null
        return ships.firstOrNull { ship ->
            candidates(ship).any { normalize(it) == query }
        } ?: ships.firstOrNull { ship ->
            candidates(ship).any { candidate ->
                val normalized = normalize(candidate)
                normalized.isNotBlank() && (normalized.contains(query) || query.contains(normalized))
            }
        }
    }

    fun displayLabel(ship: ShipCard): String {
        val zh = ship.zhName
        return if (!zh.isNullOrBlank()) "$zh (${ship.name}) (${ship.id})" else "${ship.name} (${ship.id})"
    }

    private fun candidates(ship: ShipCard): List<String> = buildList {
        add(ship.id)
        add(ship.name)
        add(displayLabel(ship))
        ship.zhName?.takeIf { it.isNotBlank() }?.let(::add)
        manufacturerPrefixes.forEach { prefix ->
            add("$prefix ${ship.name}")
            ship.zhName?.takeIf { it.isNotBlank() }?.let { add("$prefix $it") }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(punctuation, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
