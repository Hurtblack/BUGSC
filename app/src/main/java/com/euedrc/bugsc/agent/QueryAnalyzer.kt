package com.euedrc.bugsc.agent

class QueryAnalyzer(private val entityIndex: AgentEntityIndex = AgentEntityIndex()) {

    fun analyze(rawText: String): AgentQuery {
        val normalized = normalize(rawText)
        val intents = scoreIntents(normalized)
        val entities = findEntities(normalized)
        return AgentQuery(rawText, normalized, intents, entities)
    }

    fun normalize(value: String): String = value
        .let(AgentAliasNormalizer::normalize)

    private fun scoreIntents(text: String): List<ScoredIntent> {
        val scores = linkedMapOf(
            AgentIntent.SELF_HELP to score(text, "你是谁", "你能做什么", "怎么用", "什么关系", "key", "api key", "上传", "模型"),
            AgentIntent.SHIP_INFO to score(text, "船", "飞船", "硬点", "火力", "配装", "组件", "shield", "weapon", "f7a"),
            AgentIntent.MINING to score(text, "矿", "采", "挖", "精炼", "ore", "mining"),
            AgentIntent.BLUEPRINT to score(text, "蓝图", "材料", "制作", "怎么做", "怎么弄", "如何做", "合成", "配方", "craft", "blueprint"),
            AgentIntent.MISSION to score(text, "任务", "声望", "奖励", "合约", "哪里来", "哪里拿"),
            AgentIntent.MARKET to score(text, "哪里买", "价格", "市场", "出售", "scm"),
            AgentIntent.WIKELO to score(text, "维科洛", "wikelo", "兑换"),
        )
        val positive = scores
            .filterValues { it > 0 }
            .map { ScoredIntent(it.key, it.value) }
            .sortedWith(compareByDescending<ScoredIntent> { it.score }.thenBy { it.intent.ordinal })
        return positive.ifEmpty { listOf(ScoredIntent(AgentIntent.UNKNOWN, 1)) }
    }

    private fun score(text: String, vararg keywords: String): Int =
        keywords.count { text.contains(it.lowercase()) }

    private fun findEntities(text: String): List<ScoredEntity> {
        val compactText = AgentAliasNormalizer.compact(text)
        return entityIndex.entries.mapNotNull { entity ->
            val expandedAliases = AgentAliasNormalizer.expandAliases(
                entity.displayName,
                entity.value,
                *entity.aliases.toTypedArray(),
            )
            val matchedAlias = expandedAliases.firstOrNull { alias ->
                alias.isNotBlank() && (text.contains(alias) || compactText.contains(alias.replace(" ", "")))
            }
            matchedAlias?.let {
                ScoredEntity(
                    type = entity.type,
                    value = entity.value,
                    displayName = entity.displayName,
                    score = it.length,
                )
            }
        }.sortedByDescending { it.score }
    }
}
