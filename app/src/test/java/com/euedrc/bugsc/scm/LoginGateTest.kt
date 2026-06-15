package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginGateTest {

    @Test
    fun loggedInExecutesAction() {
        val decision = LoginGate.decide(isLoggedIn = true, returnDestId = 123)
        assertEquals(LoginGate.Decision.Execute, decision)
    }

    @Test
    fun notLoggedInNavigatesWithReturnDest() {
        val decision = LoginGate.decide(isLoggedIn = false, returnDestId = 123)
        assertEquals(LoginGate.Decision.Navigate(123), decision)
    }

    @Test
    fun notLoggedInNavigatesWithReturnDestAndArgumentsFlag() {
        val decision = LoginGate.decide(isLoggedIn = false, returnDestId = 123, hasReturnArgs = true)
        assertEquals(LoginGate.Decision.Navigate(123, hasReturnArgs = true), decision)
    }
}
