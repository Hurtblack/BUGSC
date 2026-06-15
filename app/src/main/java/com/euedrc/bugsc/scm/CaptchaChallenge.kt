package com.euedrc.bugsc.scm

import org.json.JSONArray
import org.json.JSONObject

/** `POST /system/captcha/get` 返回的文字点选验证码挑战。 */
data class CaptchaChallenge(
    val token: String,
    val secretKey: String,
    val originalImageBase64: String,
    val wordList: List<String>,
) {
    companion object {
        /**
         * 解析 AJ-Captcha 标准信封 `{repCode, repMsg, repData}`（不同于会员接口的 code/data/msg）。
         * repCode "0000" 为成功。
         */
        fun parse(body: String): CaptchaChallenge? = parse(JSONObject(body))

        fun parse(json: JSONObject): CaptchaChallenge? {
            if (json.optString("repCode") != "0000") return null
            val data = if (json.isNull("repData")) null else json.optJSONObject("repData")
            data ?: return null
            val words = data.optJSONArray("wordList") ?: JSONArray()
            return CaptchaChallenge(
                token = data.optString("token"),
                secretKey = data.optString("secretKey"),
                originalImageBase64 = data.optString("originalImageBase64"),
                wordList = (0 until words.length()).map { words.optString(it) },
            )
        }

        /** pointList → AJ-Captcha pointJson 字符串。 */
        fun pointJson(points: List<CaptchaCoord.Point>): String =
            points.joinToString(prefix = "[", postfix = "]", separator = ",") {
                """{"x":${it.x},"y":${it.y}}"""
            }
    }
}
