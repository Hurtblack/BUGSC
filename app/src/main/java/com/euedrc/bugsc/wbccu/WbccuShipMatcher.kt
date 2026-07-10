package com.euedrc.bugsc.wbccu

import com.euedrc.bugsc.InventoryShipPrice
import java.util.Locale

data class WbccuShipMatch(
    val name: String,
    val displayName: String,
    val priceCents: Int
)

object WbccuShipMatcher {

    fun match(
        query: String,
        shipPrices: List<InventoryShipPrice>,
        shipAliases: Map<String, String>
    ): WbccuShipMatch? {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank()) return null
        val normalizedQuery = normalize(cleanedQuery)
        val candidates = shipPrices
            .filter { it.name.isNotBlank() && it.priceCents > 0 }
            .map { price ->
                Candidate(
                    name = price.name,
                    displayName = shipAliases[price.name].orEmpty(),
                    priceCents = price.priceCents
                )
            }
            .distinctBy { normalize(it.name) }

        return candidates
            .mapNotNull { candidate ->
                val score = score(candidate, cleanedQuery, normalizedQuery)
                if (score == null) null else score to candidate
            }
            .sortedWith(
                compareBy<Pair<Int, Candidate>> { it.first }
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.name }
            )
            .firstOrNull()
            ?.second
            ?.let { candidate ->
                WbccuShipMatch(
                    name = candidate.name,
                    displayName = candidate.displayName.ifBlank { candidate.name },
                    priceCents = candidate.priceCents
                )
            }
    }

    private fun score(candidate: Candidate, rawQuery: String, normalizedQuery: String): Int? {
        val normalizedName = normalize(candidate.name)
        val normalizedDisplay = normalize(candidate.displayName)
        val rawDisplay = candidate.displayName
        return when {
            normalizedName == normalizedQuery -> 0
            normalizedDisplay.isNotBlank() && normalizedDisplay == normalizedQuery -> 0
            normalizedName.startsWith(normalizedQuery) -> 1
            normalizedDisplay.isNotBlank() && normalizedDisplay.startsWith(normalizedQuery) -> 1
            normalizedName.split(" ").any { it.startsWith(normalizedQuery) } -> 2
            normalizedName.contains(normalizedQuery) -> 3
            normalizedDisplay.isNotBlank() && normalizedDisplay.contains(normalizedQuery) -> 3
            rawDisplay.isNotBlank() && rawDisplay.contains(rawQuery, ignoreCase = true) -> 3
            else -> null
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private data class Candidate(
        val name: String,
        val displayName: String,
        val priceCents: Int
    )
}
