package com.euedrc.bugsc.wikelo

/**
 * 维克洛兑换数据模型 —— 对应 assets/wikelo/banu_trades.json + banu_materials.json。
 *
 * JSON 字段命名沿用原始数据 (snake_case → 这里转 camelCase, 保持可追溯)。
 * 数据由 4.8.0 LIVE V1 巴努交易清单整理生成。
 */

/** 兑换分支大类。 */
enum class WikeloBranch(val key: String, val labelCn: String) {
    PREREQUISITE("prerequisite", "前置"),
    VEHICLE("vehicle", "载具"),
    OTHER("other", "其他"),
    SPECIAL("special", "特别版"),
    ATLS("atls", "ATLS");

    companion object {
        fun fromKey(k: String?): WikeloBranch? = entries.firstOrNull { it.key == k }
    }
}

/** 单条兑换任务。 */
data class WikeloTrade(
    val nameEn: String,
    val nameCn: String,
    val branch: WikeloBranch,
    /** 游戏内小分类 ("0-4"/"5-8"/"雪地"/"升天"/"ATLS" 等)。 */
    val category: String,
    /** 当前版本是否可接 (黑遮任务为 false)。 */
    val available: Boolean,
    /** 兑换获得的飞船/装备/物品 (中文)。可能为 null (前置任务无获得物)。 */
    val rewardItem: String?,
    /** 人情(Wikelo Favor)花费数, 无则 null。 */
    val favorCost: Int?,
    val materials: List<WikeloMaterialReq>,
    val reputation: WikeloReputation,
    val newThisVersion: Boolean,
    val notes: String?,
    /**
     * 图片资源名 (留作 TODO): 约定放 `assets/wikelo/img/{slug}.webp`,
     * 由 UI 通过 AssetManager.open() 加载; null 表示暂无图。
     */
    val imageAsset: String? = null,
) {
    /** 是否为前置任务 (展示用)。 */
    val isPrerequisite: Boolean get() = branch == WikeloBranch.PREREQUISITE
}

/** 兑换所需的一个材料项。 */
data class WikeloMaterialReq(
    val nameCn: String,
    /** 数量, null 表示原图未能读出 (理论上当前数据已全部补齐, 不应为 null)。 */
    val qty: Int?,
    /** 单位, 默认 "个" 时为 null; 常见值: scu。 */
    val unit: String?,
)

/** 任务的声望系统字段。 */
data class WikeloReputation(
    /** 完成可获声望经验。0 表示不给经验; null 表示数据缺失。 */
    val reward: Int?,
    /** 接取所需声望等级 (1/2/3); null 表示无等级要求。 */
    val requiredTier: Int?,
)

/** 维克洛声望等级阈值。 */
data class WikeloRepTier(
    val level: Int,
    val nameCn: String,
    val nameEn: String,
    val threshold: Int,
)

/** 材料表中的一条 (获取途径)。 */
data class WikeloMaterialInfo(
    val nameEn: String,
    val nameCn: String,
    /** vehicle (维克洛载具) / other (维克洛其他)。 */
    val category: String,
    /** 获取途径 (合并多来源后的去重集合)。 */
    val acquisition: List<String>,
    val note: String?,
    /** 蓝图合成配方文本 (如适用)。 */
    val synthesis: String?,
)
