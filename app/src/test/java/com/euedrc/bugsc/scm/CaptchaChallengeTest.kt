package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptchaChallengeTest {

    // 实测 /system/captcha/get 用 AJ-Captcha 标准信封 {repCode, repMsg, repData}，
    // 而非会员接口的 {code, data, msg}。repCode "0000" 为成功。
    @Test
    fun parsesAjClickWordChallenge() {
        val json = """
            {"repCode":"0000","repMsg":null,"success":true,"repData":{
              "token":"tk-123","secretKey":"0123456789abcdef",
              "originalImageBase64":"AAAA","wordList":["流","难","野"]
            }}
        """.trimIndent()
        val c = CaptchaChallenge.parse(json)!!
        assertEquals("tk-123", c.token)
        assertEquals("0123456789abcdef", c.secretKey)
        assertEquals("AAAA", c.originalImageBase64)
        assertEquals(listOf("流", "难", "野"), c.wordList)
    }

    @Test
    fun emptySecretKeyTolerated() {
        val json = """{"repCode":"0000","repData":{"token":"tk","secretKey":"","originalImageBase64":"x","wordList":["A"]}}"""
        val c = CaptchaChallenge.parse(json)!!
        assertEquals("", c.secretKey)
        assertEquals(listOf("A"), c.wordList)
    }

    @Test
    fun failureRepCodeReturnsNull() {
        assertNull(CaptchaChallenge.parse("""{"repCode":"9999","repMsg":"err","repData":null}"""))
    }

    @Test
    fun buildsPointJson() {
        val pts = listOf(CaptchaCoord.Point(140, 80), CaptchaCoord.Point(210, 50))
        assertEquals("""[{"x":140,"y":80},{"x":210,"y":50}]""", CaptchaChallenge.pointJson(pts))
    }
}
