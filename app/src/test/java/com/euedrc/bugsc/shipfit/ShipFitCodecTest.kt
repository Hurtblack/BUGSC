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

    @Test
    fun decode_extractsCodeFromNaturalTextWithFullWidthColon() {
        val payload = ShipFitPayload(
            ship = "aegs_gladius",
            slots = linkedMapOf("gun-1" to "cf337")
        )
        val code = ShipFitCodec.encode(payload).replace(":", "：")

        val decoded = ShipFitCodec.decode("这是我的配船码：$code\n你帮我解析一下")

        assertTrue(decoded is DecodeResult.Success)
        assertEquals(payload, (decoded as DecodeResult.Success).payload)
    }

    @Test
    fun decode_extractsCodeFromShareUrlParameter() {
        val payload = ShipFitPayload(
            ship = "drak_cutlass_black",
            slots = linkedMapOf("sh1" to "fr76")
        )
        val code = ShipFitCodec.encode(payload)

        val decoded = ShipFitCodec.decode("https://example.local/fit?code=$code")

        assertTrue(decoded is DecodeResult.Success)
        assertEquals(payload, (decoded as DecodeResult.Success).payload)
    }
}
