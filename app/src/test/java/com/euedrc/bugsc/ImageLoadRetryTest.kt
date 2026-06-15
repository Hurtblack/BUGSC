package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageLoadRetryTest {

    @Test
    fun retriesOnceAfterFirstFailure() {
        var attempts = 0

        val result = loadWithOneRetry {
            attempts += 1
            if (attempts == 1) null else "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(2, attempts)
    }

    @Test
    fun retriesOnceAfterFirstException() {
        var attempts = 0

        val result = loadWithOneRetry {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("network failure") else "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(2, attempts)
    }

    @Test
    fun stopsAfterTwoFailures() {
        var attempts = 0

        val result = loadWithOneRetry<String> {
            attempts += 1
            null
        }

        assertNull(result)
        assertEquals(2, attempts)
    }

    @Test
    fun doesNotRetrySuccessfulFirstAttempt() {
        var attempts = 0

        val result = loadWithOneRetry {
            attempts += 1
            "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(1, attempts)
    }
}
