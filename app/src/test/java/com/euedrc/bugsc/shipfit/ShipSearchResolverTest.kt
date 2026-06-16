package com.euedrc.bugsc.shipfit

import org.junit.Assert.assertEquals
import org.junit.Test

class ShipSearchResolverTest {

    @Test
    fun resolvesChineseShipName() {
        val ship = ShipSearchResolver.resolve("英仙座", ships())

        assertEquals("rsi_perseus", ship?.id)
    }

    @Test
    fun resolvesFullEnglishShipName() {
        val ship = ShipSearchResolver.resolve("RSI Perseus", ships())

        assertEquals("rsi_perseus", ship?.id)
    }

    @Test
    fun resolvesShipId() {
        val ship = ShipSearchResolver.resolve("rsi_perseus", ships())

        assertEquals("rsi_perseus", ship?.id)
    }

    private fun ships(): List<ShipCard> = listOf(
        ShipCard(
            id = "rsi_perseus",
            name = "Perseus",
            uexId = 145,
            imageUrl = null,
            backupImageUrl = null,
            size = "100.5/42.2/22.3m",
            crew = "7",
            cargo = "96 SCU",
            officialUrl = null,
            enginePortCount = 24,
            engineSizeScore = 24,
            slots = emptyList(),
            slotPatched = false,
            zhName = "英仙座",
        ),
        ShipCard(
            id = "rsi_polaris",
            name = "Polaris",
            uexId = 160,
            imageUrl = null,
            backupImageUrl = null,
            size = null,
            crew = null,
            cargo = null,
            officialUrl = null,
            enginePortCount = 0,
            engineSizeScore = 0,
            slots = emptyList(),
            slotPatched = false,
            zhName = "北极星",
        ),
    )
}
