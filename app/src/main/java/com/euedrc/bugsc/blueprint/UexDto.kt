package com.euedrc.bugsc.blueprint

import org.json.JSONArray
import org.json.JSONObject

/**
 * UEX API 2.0 的原始响应 DTO 及解析。
 *
 * 与项目现有风格一致,使用 org.json 解析(不引入 Retrofit/Moshi)。
 * 这里只负责「把 UEX JSON 变成强类型对象」,不做任何业务转换——
 * 转换成我们自己的领域模型见 [UexMapper]。
 *
 * 接口约定:UEX 顶层格式为 {"status":"ok","http_code":200,"data":[...]}。
 */

/** GET /categories 的一行。 */
data class UexCategory(
    val id: Int,
    val type: String?,        // "item" / "commodity" ...
    val section: String?,     // "Armor" / "Weapons" ...
    val name: String?,        // "Helmets"
    val isGameRelated: Boolean,
    val isMining: Boolean,
)

/** GET /items 的一行。 */
data class UexItem(
    val id: Int,
    val idCategory: Int,
    val idCompany: Int,
    val name: String,
    val uuid: String?,        // 游戏内物品 GUID —— 与 SCM 的 uuid 同源,用作中文对照主键
    val section: String?,
    val category: String?,
    val companyName: String?,
    val slug: String?,
    val size: String?,
    val screenshot: String?,  // 图片 URL,可能为空
    val gameVersion: String?,
    val isHarvestable: Boolean,
    val isCommodity: Boolean,
)

/** GET /items_attributes 的一行。 */
data class UexItemAttribute(
    val id: Int,
    val idItem: Int,
    val itemUuid: String?,
    val attributeName: String,   // "Damage Reduction"
    val value: String,           // "20"  (UEX 统一用字符串)
    val unit: String?,           // "%" / "°C" / ""
)

object UexDto {

    /** 取顶层 data 数组;status 非 ok 时返回空数组。 */
    private fun dataArray(root: JSONObject): JSONArray {
        if (root.optString("status") != "ok") return JSONArray()
        return root.optJSONArray("data") ?: JSONArray()
    }

    private fun JSONObject.intBool(key: String): Boolean = optInt(key, 0) == 1

    /** UEX 把空字符串当 null 用,统一成 null。 */
    private fun JSONObject.strOrNull(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    fun parseCategories(root: JSONObject): List<UexCategory> {
        val arr = dataArray(root)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UexCategory(
                id = o.optInt("id"),
                type = o.strOrNull("type"),
                section = o.strOrNull("section"),
                name = o.strOrNull("name"),
                isGameRelated = o.intBool("is_game_related"),
                isMining = o.intBool("is_mining"),
            )
        }
    }

    fun parseItems(root: JSONObject): List<UexItem> {
        val arr = dataArray(root)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UexItem(
                id = o.optInt("id"),
                idCategory = o.optInt("id_category"),
                idCompany = o.optInt("id_company"),
                name = o.optString("name"),
                uuid = o.strOrNull("uuid"),
                section = o.strOrNull("section"),
                category = o.strOrNull("category"),
                companyName = o.strOrNull("company_name"),
                slug = o.strOrNull("slug"),
                size = o.strOrNull("size"),
                screenshot = o.strOrNull("screenshot"),
                gameVersion = o.strOrNull("game_version"),
                isHarvestable = o.intBool("is_harvestable"),
                isCommodity = o.intBool("is_commodity"),
            )
        }
    }

    fun parseItemAttributes(root: JSONObject): List<UexItemAttribute> {
        val arr = dataArray(root)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UexItemAttribute(
                id = o.optInt("id"),
                idItem = o.optInt("id_item"),
                itemUuid = o.strOrNull("item_uuid"),
                attributeName = o.optString("attribute_name"),
                value = o.optString("value"),
                unit = o.strOrNull("unit"),
            )
        }
    }
}
