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

    @Test
    fun resolvesCommonChineseShipAliases() {
        assertEquals("gama_syulen", ShipSearchResolver.resolve("速伦", ships())?.id)
        assertEquals("railen", ShipSearchResolver.resolve("锐伦", ships())?.id)
        assertEquals("railen", ShipSearchResolver.resolve("锐轮", ships())?.id)
        assertEquals("aegs_tiburon", ShipSearchResolver.resolve("提布龙", ships())?.id)
        assertEquals("aegs_tiburon", ShipSearchResolver.resolve("泰伯伦", ships())?.id)
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
        ShipCard(
            id = "gama_syulen",
            name = "Syulen",
            uexId = 188,
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
            zhName = "絮伦",
        ),
        ShipCard(
            id = "railen",
            name = "Railen",
            uexId = 152,
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
            zhName = "瑞伦",
        ),
        ShipCard(
            id = "aegs_tiburon",
            name = "Tiburon",
            uexId = 285,
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
            zhName = "鲨鱼",
        ),
    )
}
