package com.euedrc.bugsc.blueprint

/**
 * UEX DTO → 自有领域模型的转换层,并在转换时叠加本地中文翻译。
 *
 * 设计要点:
 *  - 数据来源(UEX)与中文翻译(SCM 一次性导出的本地表)在此处汇合;
 *  - 转换是纯函数,无网络/IO,便于单测;
 *  - 物品属性按 uuid / 英文名对照中文,缺失则回退英文。
 */
object UexMapper {

    fun toCategory(dto: UexCategory, t: CodexTranslations = CodexTranslations.EMPTY): CodexCategory =
        CodexCategory(
            id = dto.id,
            section = dto.section,
            nameEn = dto.name,
            nameCn = t.categoryName(dto.name),
            isMining = dto.isMining,
        )

    fun toCategories(dtos: List<UexCategory>, t: CodexTranslations = CodexTranslations.EMPTY): List<CodexCategory> =
        dtos.map { toCategory(it, t) }

    fun toAttribute(dto: UexItemAttribute, t: CodexTranslations = CodexTranslations.EMPTY): CodexAttribute =
        CodexAttribute(
            nameEn = dto.attributeName,
            nameCn = t.attributeName(dto.attributeName),
            rawValue = dto.value,
            unit = dto.unit,
        )

    /**
     * 合并一个物品和它的属性行。
     * @param attributes 该物品对应的 items_attributes 行(可只传匹配 idItem/uuid 的子集)
     */
    fun toItem(
        item: UexItem,
        attributes: List<UexItemAttribute>,
        t: CodexTranslations = CodexTranslations.EMPTY,
    ): CodexItem {
        val mine = attributes.filter { it.idItem == item.id || (it.itemUuid != null && it.itemUuid == item.uuid) }
        return CodexItem(
            id = item.id,
            uuid = item.uuid,
            nameEn = item.name,
            nameCn = t.itemName(item.name),
            descriptionCn = null,
            categoryId = item.idCategory,
            categoryEn = item.category,
            categoryCn = t.categoryName(item.category),
            section = item.section,
            company = item.companyName,
            imageUrl = item.screenshot,
            gameVersion = item.gameVersion,
            isHarvestable = item.isHarvestable,
            attributes = mine.map { toAttribute(it, t) },
        )
    }

    /**
     * 批量合并:把一批物品与一批属性行按 idItem / uuid 关联起来。
     * 适合「先拉某分类的全部 items + 该分类的全部 items_attributes」后一次性组装。
     */
    fun toItems(
        items: List<UexItem>,
        attributes: List<UexItemAttribute>,
        t: CodexTranslations = CodexTranslations.EMPTY,
    ): List<CodexItem> {
        val byItemId = attributes.groupBy { it.idItem }
        val byUuid = attributes.filter { it.itemUuid != null }.groupBy { it.itemUuid }
        return items.map { item ->
            val attrs = byItemId[item.id] ?: item.uuid?.let { byUuid[it] } ?: emptyList()
            toItem(item, attrs, t)
        }
    }
}
