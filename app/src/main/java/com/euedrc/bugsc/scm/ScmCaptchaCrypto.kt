package com.euedrc.bugsc.scm

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AJ-Captcha 文字点选验证码加密。
 *
 * 规则：captchaVerification = Base64(AES/ECB/PKCS5Padding(token + "---" + pointJson, secretKey))。
 * secretKey 为空时不加密，直接返回 "token---pointJson" 明文。
 *
 * Base64 自实现：避开 android.util.Base64（JVM 单测里是未 mock 的空桩）与
 * java.util.Base64（requires API 26 > minSdk 24）。
 */
object ScmCaptchaCrypto {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 登录用：captchaVerification = AES(token + "---" + pointJson, secretKey)，空密钥则明文。 */
    fun buildVerification(token: String, pointJson: String, secretKey: String): String =
        aesBase64("$token---$pointJson", secretKey)

    /** check 接口用：pointJson 字段 = AES(pointJson, secretKey)（不含 token），空密钥则明文。 */
    fun encryptPointJson(pointJson: String, secretKey: String): String =
        aesBase64(pointJson, secretKey)

    private fun aesBase64(plain: String, secretKey: String): String {
        if (secretKey.isEmpty()) return plain
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val key = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return base64Encode(encrypted)
    }

    private fun base64Encode(input: ByteArray): String {
        val out = StringBuilder((input.size + 2) / 3 * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triple shr 18) and 0x3F])
            out.append(ALPHABET[(triple shr 12) and 0x3F])
            out.append(if (i + 1 < input.size) ALPHABET[(triple shr 6) and 0x3F] else '=')
            out.append(if (i + 2 < input.size) ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}
