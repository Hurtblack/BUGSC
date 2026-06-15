package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmAuthSessionTest {

    private class FakeKv : ScmKvStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun saveThenSessionRoundTrips() {
        val auth = ScmAuthSession(FakeKv())
        auth.save(AppAuthLoginRespVO(userId = 555, accessToken = "at", refreshToken = "rt", expiresTime = 999))

        assertTrue(auth.isLoggedIn)
        val s = auth.session()
        assertEquals("at", s.accessToken)
        assertEquals("rt", s.refreshToken)
        assertEquals(999L, s.expiresTime)
        assertEquals(555L, s.userId)
    }

    @Test
    fun notLoggedInWithoutToken() {
        assertFalse(ScmAuthSession(FakeKv()).isLoggedIn)
    }

    @Test
    fun clearRemovesTokensButKeepsDeviceId() {
        val kv = FakeKv()
        val auth = ScmAuthSession(kv)
        val device = auth.ensureDeviceId()
        auth.save(AppAuthLoginRespVO(1, "at", "rt", 1))

        auth.clear()

        assertFalse(auth.isLoggedIn)
        assertEquals("", auth.session().accessToken)
        // deviceId 是设备身份，登出不应清除
        assertEquals(device, auth.session().deviceId)
    }

    @Test
    fun ensureDeviceIdGeneratesOnceThenStable() {
        val auth = ScmAuthSession(FakeKv())
        val first = auth.ensureDeviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, auth.ensureDeviceId())
        assertEquals(first, auth.session().deviceId)
    }
}
