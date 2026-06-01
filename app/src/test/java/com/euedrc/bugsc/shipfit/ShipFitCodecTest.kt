package com.euedrc.bugsc.shipfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipFitCodecTest {

    @Test
    fun encodeDecode_roundTrip_ok() {
        val payload = ShipFitPayload(
            ship = "drak_cutlass_black",
            slots = linkedMapOf("w1" to "laser_cf337", "sh1" to "fr76")
        )
        val code = ShipFitCodec.encode(payload)
        assertTrue(code.startsWith("BUGFIT:v1:"))
        val decoded = ShipFitCodec.decode(code)
        assertTrue(decoded is DecodeResult.Success)
        val success = decoded as DecodeResult.Success
        assertEquals(payload.ship, success.payload.ship)
        assertEquals(payload.slots, success.payload.slots)
    }

    @Test
    fun decode_invalidPrefix_error() {
        val result = ShipFitCodec.decode("BAD:v1:xxx")
        assertTrue(result is DecodeResult.Error)
        assertEquals("INVALID_PREFIX", (result as DecodeResult.Error).code)
    }
}

