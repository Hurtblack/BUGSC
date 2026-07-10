package com.euedrc.bugsc.agent

import com.euedrc.bugsc.shipfit.ShipCard
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShipSearchFormatterTest {

    @Test
    fun includesSalePriceInShipFactsAndSummary() {
        val hit = AgentShipSearchFormatter.hit(
            ship = ShipCard(
                id = "crus_starlifter_c2",
                name = "C2 Hercules",
                uexId = 162,
                imageUrl = null,
                backupImageUrl = null,
                size = "94/70/23m",
                crew = "1-2",
                cargo = "696 SCU",
                salePriceCents = 40000,
                officialUrl = null,
                enginePortCount = 0,
                engineSizeScore = 0,
                slots = emptyList(),
                slotPatched = false,
                zhName = "大力神 C2",
            ),
            score = 10,
        )

        assertTrue(hit.summary.contains("售卖价 $400"))
        assertTrue(hit.facts.any { it.label == "售卖价" && it.value == "$400" })
    }
}
