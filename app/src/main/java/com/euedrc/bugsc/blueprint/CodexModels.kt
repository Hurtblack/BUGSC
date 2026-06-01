package com.euedrc.bugsc.blueprint

import android.content.Context
import org.json.JSONObject

/**
 * 我们自己的图鉴领域模型(数据源无关)。
 *
 * UEX 的 DTO 经 [UexMapper] 转换、叠加中文翻译后得到这些对象,供 UI 渲染;
 * 其中数值属性还可转成计算引擎需要的 baseStats。
 */

/** 图鉴分类。 */
data class CodexCategory(
    val id: Int,
    val section: String?,
    val nameEn: String?,
    val nameCn: String?,
    val isMining: Boolean,
) {
    /** 优先中文,缺失回退英文。 */
    val displayName: String? get() = nameCn ?: nameEn
}

/** 图鉴中一条物品属性,如 "伤害减免 / Damage Reduction = 20 %"。 */
data class CodexAttribute(
    val nameEn: String,
    val nameCn: String?,
    val rawValue: String,
    val unit: String?,
) {
    val displayName: String get() = nameCn ?: nameEn

    /** 尝试解析成数值(供计算引擎/排序用);非数值返回 null。 */
    val numericValue: Double? get() = rawValue.trim().toDoubleOrNull()
}

/** 图鉴物品。 */
data class CodexItem(
    val id: Int,
    val uuid: String?,
    val nameEn: String,
    val nameCn: String?,
    val descriptionCn: String? = null,
    val categoryId: Int,
    val categoryEn: String?,
    val categoryCn: String?,
    val section: String?,
    val company: String?,
    val imageUrl: String?,
    val gameVersion: String?,
    val isHarvestable: Boolean,
    val attributes: List<CodexAttribute>,
) {
    val displayName: String get() = nameCn ?: nameEn

    /**
     * 把可数值化的属性提取成 { attributeNameEn -> value },
     * 作为计算引擎 [Blueprint.baseStats] 的输入基础。
     * 注意:这里的 key 用英文属性名;真正喂给引擎前可能还需映射到
     * gameplayProperty(如 "Damage Reduction" -> "Weapon_Damage"),
     * 该映射属于配方数据的一部分,后续接入。
     */
    fun numericAttributeMap(): Map<String, Double> =
        attributes.mapNotNull { attr -> attr.numericValue?.let { attr.nameEn to it } }.toMap()
}

/**
 * 中文翻译表:一次性从 SCM 导出后,以本地数据形式注入,运行时不依赖外部站点。
 *
 * 对照 key 用**英文名**:UEX 与 SCM 都带英文名(itemNameEn),无需逐个查 uuid。
 * - [itemNameByEn]:物品英文名 -> 中文名
 * - [attributeNameByEn] / [categoryNameByEn]:属性/分类英文名 -> 中文(可后续补充)
 */
class CodexTranslations(
    private val itemNameByEn: Map<String, String> = emptyMap(),
    private val attributeNameByEn: Map<String, String> = emptyMap(),
    private val categoryNameByEn: Map<String, String> = emptyMap(),
) {
    /** 物品中文名:按英文名查,缺失返回 null(由调用方回退英文)。 */
    fun itemName(nameEn: String?): String? = nameEn?.let { itemNameByEn[it] }
    fun attributeName(en: String): String? = attributeNameByEn[en]
    fun categoryName(en: String?): String? = en?.let { categoryNameByEn[it] }

    val size: Int get() = itemNameByEn.size

    /**
     * 合并额外物品译名。默认保留当前表里的译名,用于让专项校准过的表覆盖通用表。
     */
    fun withItemNames(extra: Map<String, String>, overrideExisting: Boolean = false): CodexTranslations {
        if (extra.isEmpty()) return this
        val merged = LinkedHashMap<String, String>(itemNameByEn.size + extra.size)
        if (overrideExisting) {
            merged.putAll(itemNameByEn)
            merged.putAll(extra)
        } else {
            merged.putAll(extra)
            merged.putAll(itemNameByEn)
        }
        return CodexTranslations(
            itemNameByEn = merged,
            attributeNameByEn = attributeNameByEn,
            categoryNameByEn = categoryNameByEn,
        )
    }

    companion object {
        /** 空表:无中文时图鉴回退英文,功能不受影响。 */
        val EMPTY = CodexTranslations()

        /**
         * 从一次性导出的本地 JSON 构建翻译表(assets/blueprint/scm_translations.json)。
         * 约定格式:
         * {
         *   "items_by_en": { "Origin M80": "起源 M80", ... },
         *   "attributes":  { "Damage Reduction": "伤害减免", ... },
         *   "categories":  { "Helmets": "头盔", ... }
         * }
         */
        fun fromJson(root: JSONObject): CodexTranslations =
            CodexTranslations(
                itemNameByEn = root.optJSONObject("items_by_en").toStringMap(),
                attributeNameByEn = root.optJSONObject("attributes").toStringMap(),
                categoryNameByEn = root.optJSONObject("categories").toStringMap(),
            )

        /**
         * 从 assets 读取翻译表(默认 blueprint/scm_translations.json)。
         * 请在 IO 线程调用;失败时回退 [EMPTY],不影响图鉴(只是无中文)。
         */
        fun fromAssets(
            context: Context,
            path: String = "blueprint/scm_translations.json",
        ): CodexTranslations = runCatching {
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            fromJson(JSONObject(text))
        }.getOrDefault(EMPTY)

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val map = HashMap<String, String>()
            for (key in keys()) {
                optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it }
            }
            return map
        }
    }
}
