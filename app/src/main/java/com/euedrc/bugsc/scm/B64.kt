package com.euedrc.bugsc.scm

/**
 * 纯 Kotlin 标准 Base64（RFC 4648）编解码。
 * 避开 android.util.Base64（JVM 单测空桩）与 java.util.Base64（需 API26 > minSdk24）。
 */
object B64 {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(input: ByteArray): String {
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

    fun decode(input: String): ByteArray {
        val clean = input.filter { it != '\n' && it != '\r' }
        val padless = clean.trimEnd('=')
        val outLen = padless.length * 6 / 8
        val out = ByteArray(outLen)
        var buffer = 0
        var bits = 0
        var oi = 0
        for (c in padless) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[oi++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}
