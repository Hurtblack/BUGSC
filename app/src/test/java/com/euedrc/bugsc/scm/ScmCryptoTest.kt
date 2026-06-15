package com.euedrc.bugsc.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScmCryptoTest {

    @Test
    fun rsaEncryptProduces128ByteCiphertext() {
        val cipher = ScmCrypto.rsaEncryptPassword("my-secret-pass")
        // RSA-1024 → 128 字节密文
        assertEquals(128, B64.decode(cipher).size)
        assertTrue(cipher.isNotEmpty())
        assertNotEquals("my-secret-pass", cipher)
    }

    @Test
    fun rsaEncryptIsNonDeterministic() {
        // PKCS#1 v1.5 随机填充 → 两次结果不同
        assertNotEquals(
            ScmCrypto.rsaEncryptPassword("same"),
            ScmCrypto.rsaEncryptPassword("same"),
        )
    }
}
