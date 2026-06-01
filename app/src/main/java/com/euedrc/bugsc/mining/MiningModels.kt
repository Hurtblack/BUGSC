package com.euedrc.bugsc.mining

/**
 * 采矿数据模型 —— 对应 sm.scmdb.net 的 mining_data.json / mining_equipment.json。
 *
 * 字段命名沿用源数据 (保持可追溯),但通过 Repository 暴露时会做中文化适配。
 *
 * 三层关联:
 *   MiningElement (单矿物)  ── parts ──>  MiningComposition (混合矿石)
 *                                                  │
 *                                                  └── 引用 ──>  MiningLocation (开采地点)
 */

/** 单个矿物 (element) —— 对应 mining_data.json.mineableElements[guid]。 */
data class MiningElement(
    val guid: String,
    val nameEn: String,
    val nameCn: String?,                 // 中文名 (来自 element_translations.json), 缺失为 null
    val rarity: String?,                 // common/uncommon/rare/epic/legendary, null 表示 FPS 手持矿
    val density: Double,
    val instability: Double,
    val resistance: Double,
    val optimalWindowMidpoint: Double,
    val optimalWindowRandomness: Double,
    val optimalWindowThinness: Double,
    val explosionMultiplier: Double,
    val clusterFactor: Double,
    val scanSignature: Int?,             // 飞船扫描签名 (船采)
    val fpsScanSignature: Int?,          // FPS 手持扫描签名 (手采)
    val groundScanSignature: Int?,
) {
    val displayName: String get() = nameCn ?: nameEn
    val isFpsOnly: Boolean get() = rarity == null && fpsScanSignature != null
}

/** 矿石组合 —— 一种矿石中各 element 的混合配方。 */
data class MiningComposition(
    val guid: String,
    val name: String,
    val minimumDistinctElements: Int,
    val parts: List<CompositionPart>,
)

data class CompositionPart(
    val elementGuid: String,
    val elementName: String,
    val probability: Double,             // 此 element 在该组合中出现的概率 (0~1)
    val minPercent: Double,              // 出现时, 占组合矿石的最小百分比
    val maxPercent: Double,              // 出现时, 占组合矿石的最大百分比
)

/** 开采地点 —— 一个 location 含若干 group, 每个 group 含若干 deposit。 */
data class MiningLocation(
    val locationName: String,            // 英文原名
    val locationNameCn: String?,         // 中文名 (来自 location_translations.json), 缺失为 null
    val system: String,                  // 英文星系名
    val systemCn: String?,
    val locationType: String,            // planet / moon / event / belt ...
    val locationTypeCn: String?,
    val groups: List<MiningGroup>,
) {
    /** 双语展示, 如 "赫尔斯顿 (Hurston)"。缺翻译时只返回英文。 */
    val displayName: String get() = locationNameCn?.let { "$it ($locationName)" } ?: locationName
    val systemDisplay: String get() = systemCn?.let { "$it/$system" } ?: system
}

data class MiningGroup(
    val groupName: String,
    val groupProbability: Double,        // 权重 (非 0~1, 跨 group 比较意义有限)
    val deposits: List<MiningDeposit>,
)

/** 一处 deposit —— 可能引用 composition (矿石) 或 preset (船骸/植物等)。 */
data class MiningDeposit(
    val compositionGuid: String?,        // null 表示这条是 salvage / plant, 不是矿
    val presetName: String?,             // 非矿的 preset 名 (例: "Salvage 890")
    val relativeProbability: Double,     // 组内相对权重, 需归一化得概率
    /** 在所属 group 内的归一化概率 (0~1), Repository 构造时算好。 */
    val probabilityInGroup: Double,
)

/** ───── 查询结果 (面向 UI 的扁平视图) ───── */

/** "这个矿物出现在哪些地点 / 概率多少 / 含量多少" 的一行。 */
data class ElementOccurrence(
    val element: MiningElement,
    val composition: MiningComposition,
    val part: CompositionPart,
    val location: MiningLocation,
    val groupName: String,
    val probabilityInGroup: Double,      // composition 在该 group 内被选中的概率
) {
    /** 综合概率粗估: 组内 deposit 概率 × 该 element 在组合中出现概率。 */
    val overallProbability: Double get() = probabilityInGroup * part.probability
    val minPercent: Double get() = part.minPercent
    val maxPercent: Double get() = part.maxPercent
}

/** "这个地点能挖到哪些矿 / 概率多少" 的一行。 */
data class LocationYield(
    val location: MiningLocation,
    val groupName: String,
    val composition: MiningComposition,
    val probabilityInGroup: Double,
    /** 该 composition 里所有 element 的元数据 (带翻译)。 */
    val elements: List<Pair<MiningElement, CompositionPart>>,
)

/** ───── 装备 (mining_equipment.json) ───── */

data class MiningLaser(
    val name: String,
    val size: Int?,                      // 1/2/...
    val grade: Int?,                     // 1=A 2=B ...
    val manufacturer: String?,
    val raw: org.json.JSONObject,        // 原始 JSON, UI 按需读字段
)

data class MiningModule(val name: String, val raw: org.json.JSONObject)
data class MiningGadget(val name: String, val raw: org.json.JSONObject)
