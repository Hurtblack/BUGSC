package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Test

class ScmCaptchaCryptoTest {

    private val token = "test-token"
    private val pointJson = """[{"x":140,"y":80},{"x":210,"y":50}]"""

    @Test
    fun encryptsWithAesEcbPkcs5AndBase64() {
        // Independent oracle (openssl aes-128-ecb, PKCS7 == Java PKCS5):
        //   plaintext = "test-token---[{"x":140,"y":80},{"x":210,"y":50}]"
        //   key       = "0123456789abcdef"
        val expected =
            "z2v+AJr/K1L1VBwnqgXX80xQf/xIt3smi2sV9Yf9gvyZdSBzTwOKHJELHCgZ3KY0N3Ii4GGpJMWRzZwn6hY+1A=="

        val result = ScmCaptchaCrypto.buildVerification(token, pointJson, "0123456789abcdef")

        assertEquals(expected, result)
    }

    @Test
    fun emptySecretKeyReturnsPlaintextVerification() {
        val result = ScmCaptchaCrypto.buildVerification(token, pointJson, "")

        assertEquals("$token---$pointJson", result)
    }

    @Test
    fun encryptsPointJsonForCheckCall() {
        // check 接口的 pointJson 字段 = AES(pointJson, secretKey)（仅 pointJson，不含 token）
        val expected = "SE4kSgy8YG/rsIFYNlAkCObCWeyk15hpnnC/DksZd+HeZhl1fr7y57r9qWITibR5"
        val result = ScmCaptchaCrypto.encryptPointJson(pointJson, "0123456789abcdef")
        assertEquals(expected, result)
    }

    @Test
    fun encryptPointJsonEmptyKeyIsPlaintext() {
        assertEquals(pointJson, ScmCaptchaCrypto.encryptPointJson(pointJson, ""))
    }
}
