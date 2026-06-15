package com.euedrc.bugsc.scm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class B64Test {

    @Test
    fun encodesRfc4648Vectors() {
        assertEquals("", B64.encode("".toByteArray()))
        assertEquals("Zg==", B64.encode("f".toByteArray()))
        assertEquals("Zm8=", B64.encode("fo".toByteArray()))
        assertEquals("Zm9v", B64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", B64.encode("foob".toByteArray()))
        assertEquals("Zm9vYmFy", B64.encode("foobar".toByteArray()))
    }

    @Test
    fun decodesRfc4648Vectors() {
        assertArrayEquals("f".toByteArray(), B64.decode("Zg=="))
        assertArrayEquals("fo".toByteArray(), B64.decode("Zm8="))
        assertArrayEquals("foobar".toByteArray(), B64.decode("Zm9vYmFy"))
    }

    @Test
    fun roundTripsArbitraryBytes() {
        val data = ByteArray(256) { (it - 128).toByte() }
        assertArrayEquals(data, B64.decode(B64.encode(data)))
    }
}
