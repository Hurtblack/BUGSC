package com.euedrc.bugsc.shipfit

import org.json.JSONObject
import java.util.Base64

data class ShipFitPayload(
    val ship: String,
    val slots: Map<String, String>,
)

sealed class DecodeResult {
    data class Success(val payload: ShipFitPayload) : DecodeResult()
    data class Error(val code: String, val message: String) : DecodeResult()
}

object ShipFitCodec {
    private const val PREFIX = "BUGFIT"
    private const val VERSION = "v1"

    fun encode(payload: ShipFitPayload): String {
        val json = JSONObject().apply {
            put("ship", payload.ship)
            put("s", JSONObject(payload.slots))
        }
        val b64 = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "$PREFIX:$VERSION:$b64"
    }

    fun decode(code: String): DecodeResult {
        val parts = code.trim().split(":")
        if (parts.size != 3 || parts[0] != PREFIX) {
            return DecodeResult.Error("INVALID_PREFIX", "不是有效的 BUGFIT 配船码")
        }
        if (parts[1] != VERSION) {
            return DecodeResult.Error("UNSUPPORTED_VERSION", "不支持版本: ${parts[1]}")
        }
        val decoded = try {
            String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        } catch (_: Exception) {
            return DecodeResult.Error("INVALID_BASE64", "配船码损坏（Base64 解析失败）")
        }
        val json = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            return DecodeResult.Error("INVALID_JSON", "配船码损坏（JSON 解析失败）")
        }
        val ship = json.optString("ship")
        if (ship.isBlank()) {
            return DecodeResult.Error("INVALID_SHAPE", "配船码结构不合法：ship 不能为空")
        }
        val slotsJson = json.optJSONObject("s")
            ?: return DecodeResult.Error("INVALID_SHAPE", "配船码结构不合法：缺少 s")
        val slots = linkedMapOf<String, String>()
        val keys = slotsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = slotsJson.optString(key)
            if (key.isBlank() || value.isBlank()) {
                return DecodeResult.Error("INVALID_SHAPE", "配船码结构不合法：槽位键值不能为空")
            }
            slots[key] = value
        }
        return DecodeResult.Success(ShipFitPayload(ship = ship, slots = slots))
    }
}
