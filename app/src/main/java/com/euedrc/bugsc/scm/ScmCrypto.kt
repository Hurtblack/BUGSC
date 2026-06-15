package com.euedrc.bugsc.scm

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * SCM 密码 RSA 加密：与官网一致，用固定公钥 RSA/ECB/PKCS1Padding 加密后 Base64。
 * 后端按已加密解密，传明文会报"密码错误"。
 */
object ScmCrypto {

    // 官网 fc.js 内嵌公钥（JSEncrypt，1024-bit）。
    private const val PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQD3eRZidjmrnCw0iAQ8Z090xxFDkLb3z3nh5kb5SnMGwh2" +
        "pdc1+YmY4L/ApJG8PaQphnV/g/vyZ9VLj9DbjVyLIsk9755Jtml/i0BLCHpIdNnwAw01l9r58ieQ8NfKLLe" +
        "eHHVcaE12z9YVj1YCHTbImDOu47+CD9gvE95CMDJjzBQIDAQAB"

    fun rsaEncryptPassword(plain: String): String {
        val keySpec = X509EncodedKeySpec(B64.decode(PUBLIC_KEY))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return B64.encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}
