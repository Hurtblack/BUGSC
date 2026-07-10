package com.euedrc.bugsc.shipfit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipFitDataRepositoryTest {

    @Test
    fun createsSearchableUexOnlyShipWithoutSlots() {
        val ship = ShipFitDataRepository.createUexOnlyShipCard(
            JSONObject(
                """
                {
                  "id": 152,
                  "slug": "railen",
                  "name": "Railen",
                  "scu": 640,
                  "crew": "4",
                  "sale_price_cents": 22500,
                  "size": {"length": 53, "width": 52, "height": 67},
                  "url_photo": "https://example.com/railen.jpg",
                  "url_store": "https://robertsspaceindustries.com/en/pledge/Standalone-Ships/Railen"
                }
                """.trimIndent(),
            ),
            zhName = "瑞伦",
        )

        assertNotNull(ship)
        requireNotNull(ship)
        assertEquals("railen", ship.id)
        assertEquals("Railen", ship.name)
        assertEquals("瑞伦", ship.zhName)
        assertEquals("53/52/67m", ship.size)
        assertEquals("4", ship.crew)
        assertEquals("640 SCU", ship.cargo)
        assertEquals(22500, ship.salePriceCents)
        assertTrue(ship.slots.isEmpty())
    }
}
