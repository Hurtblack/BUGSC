package com.euedrc.bugsc.wbccu

import com.euedrc.bugsc.scm.B64
import org.json.JSONArray
import org.json.JSONObject

sealed class WbccuChainDecodeResult {
    data class Success(val chain: WbccuChain) : WbccuChainDecodeResult()
    data class Error(val code: String, val message: String) : WbccuChainDecodeResult()
}

object WbccuChainCodec {
    private const val PREFIX = "WBCCU"
    private const val VERSION = "v1"

    fun encodeChains(chains: List<WbccuChain>): String {
        val array = JSONArray()
        chains.forEach { array.put(chainToJson(it, stripInventory = false)) }
        return array.toString()
    }

    fun decodeChains(raw: String): List<WbccuChain> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { jsonToChain(array.getJSONObject(index)) }.getOrNull()
        }
    }

    fun encodeShare(chain: WbccuChain): String {
        val json = chainToJson(chain, stripInventory = true).toString()
        return "$PREFIX:$VERSION:${encodeUrlSafe(json.toByteArray(Charsets.UTF_8))}"
    }

    fun decodeShare(raw: String): WbccuChainDecodeResult {
        val normalized = extractCode(raw)
        val parts = normalized.split(":")
        if (parts.size != 3 || !parts[0].equals(PREFIX, ignoreCase = true)) {
            return WbccuChainDecodeResult.Error("INVALID_PREFIX", "不是有效的 WBCCU 分享码")
        }
        if (parts[1] != VERSION) {
            return WbccuChainDecodeResult.Error("UNSUPPORTED_VERSION", "不支持的 WBCCU 分享码版本：${parts[1]}")
        }
        val decoded = try {
            String(decodeUrlSafe(parts[2]), Charsets.UTF_8)
        } catch (_: Exception) {
            return WbccuChainDecodeResult.Error("INVALID_BASE64", "WBCCU 分享码损坏：Base64 解析失败")
        }
        val json = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            return WbccuChainDecodeResult.Error("INVALID_JSON", "WBCCU 分享码损坏：JSON 解析失败")
        }
        val chain = try {
            jsonToChain(json)
        } catch (_: Exception) {
            return WbccuChainDecodeResult.Error("INVALID_SHAPE", "WBCCU 分享码结构不合法")
        }
        return WbccuChainDecodeResult.Success(chain)
    }

    private fun chainToJson(chain: WbccuChain, stripInventory: Boolean): JSONObject {
        return JSONObject().apply {
            put("id", chain.id)
            put("name", chain.name)
            put("updatedAtMillis", chain.updatedAtMillis)
            put("steps", JSONArray().apply {
                chain.steps.forEach { step ->
                    put(stepToJson(step, stripInventory))
                }
            })
        }
    }

    private fun stepToJson(step: WbccuChainStep, stripInventory: Boolean): JSONObject {
        val exportedSource = when {
            !stripInventory -> step.source
            step.source == WbccuChainStepSource.BASE -> WbccuChainStepSource.BASE
            else -> WbccuChainStepSource.IMPORTED
        }
        return JSONObject().apply {
            put("id", step.id)
            put("fromShipName", step.fromShipName)
            put("toShipName", step.toShipName)
            put("fromShipPriceCents", step.fromShipPriceCents)
            put("toShipPriceCents", step.toShipPriceCents)
            put("paidCents", step.paidCents)
            put("saleType", step.saleType.name)
            put("source", exportedSource.name)
            put("inventoryItemId", if (stripInventory) "" else step.inventoryItemId)
            put("note", step.note)
        }
    }

    private fun jsonToChain(json: JSONObject): WbccuChain {
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).map { index ->
            jsonToStep(stepsJson.getJSONObject(index))
        }
        return WbccuChain(
            id = json.optString("id").ifBlank { newId("chain") },
            name = json.optString("name").ifBlank { "未命名 WBCCU 链路" },
            steps = steps,
            updatedAtMillis = json.optLong("updatedAtMillis", 0L)
        )
    }

    private fun jsonToStep(json: JSONObject): WbccuChainStep {
        return WbccuChainStep(
            id = json.optString("id").ifBlank { newId("step") },
            fromShipName = json.optString("fromShipName"),
            toShipName = json.optString("toShipName"),
            fromShipPriceCents = json.optInt("fromShipPriceCents"),
            toShipPriceCents = json.optInt("toShipPriceCents"),
            paidCents = json.optInt("paidCents"),
            saleType = enumValueOrDefault(json.optString("saleType"), WbccuSaleType.UNKNOWN),
            source = enumValueOrDefault(json.optString("source"), WbccuChainStepSource.IMPORTED),
            inventoryItemId = json.optString("inventoryItemId"),
            note = json.optString("note")
        )
    }

    fun newId(prefix: String): String = "$prefix-${System.currentTimeMillis()}-${(1000..9999).random()}"

    private fun extractCode(raw: String): String {
        val normalized = raw.trim().replace('：', ':')
        val match = Regex("""WBCCU\s*:\s*v1\s*:\s*([A-Za-z0-9_-]+={0,2})""", RegexOption.IGNORE_CASE)
            .find(normalized)
        return match?.let { "$PREFIX:$VERSION:${it.groupValues[1].trim()}" }
            ?: normalized.replace(Regex("""\s+"""), "")
    }

    private fun encodeUrlSafe(bytes: ByteArray): String {
        return B64.encode(bytes)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    private fun decodeUrlSafe(value: String): ByteArray {
        val standard = value
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return B64.decode(standard)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }
}
