package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInSummaryTest {

    @Test
    fun parsesSummary() {
        val json = """{"code":0,"msg":"","data":{"totalDay":42,"continuousDay":7,"todaySignIn":true}}"""
        val s = SignInSummary.parse(ScmResponse.parse(json))!!
        assertEquals(42, s.totalDay)
        assertEquals(7, s.continuousDay)
        assertTrue(s.todaySignIn)
    }

    @Test
    fun notSignedToday() {
        val json = """{"code":0,"data":{"totalDay":1,"continuousDay":0,"todaySignIn":false},"msg":""}"""
        val s = SignInSummary.parse(ScmResponse.parse(json))!!
        assertFalse(s.todaySignIn)
    }

    @Test
    fun failureReturnsNull() {
        assertNull(SignInSummary.parse(ScmResponse.parse("""{"code":500,"data":null,"msg":"err"}""")))
    }
}
