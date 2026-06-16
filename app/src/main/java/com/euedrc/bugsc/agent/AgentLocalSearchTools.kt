package com.euedrc.bugsc.agent

object AgentLocalSearchTools {
    fun create(provider: AgentSearchProvider): List<AgentTool> = listOf(
        LocalSearchTool("search_ship", "飞船资料查询", AgentIntent.SHIP_INFO, provider),
        LocalSearchTool("search_mining", "矿物资料查询", AgentIntent.MINING, provider),
        LocalSearchTool("search_blueprint", "蓝图资料查询", AgentIntent.BLUEPRINT, provider),
        LocalSearchTool("search_mission", "任务资料查询", AgentIntent.MISSION, provider),
        LocalSearchTool("search_wikelo", "维科洛兑换查询", AgentIntent.WIKELO, provider),
    )

    fun create(provider: AgentSearchProvider, entityIndex: AgentEntityIndex): List<AgentTool> =
        listOf(
            LocalSearchTool("search_ship", "飞船资料查询", AgentIntent.SHIP_INFO, provider, entityIndex),
            LocalSearchTool("search_mining", "矿物资料查询", AgentIntent.MINING, provider, entityIndex),
            LocalSearchTool("search_blueprint", "蓝图资料查询", AgentIntent.BLUEPRINT, provider, entityIndex),
            LocalSearchTool("search_mission", "任务资料查询", AgentIntent.MISSION, provider, entityIndex),
            LocalSearchTool("search_wikelo", "维科洛兑换查询", AgentIntent.WIKELO, provider, entityIndex),
            GlobalIndexTool(entityIndex),
        )
}

private class LocalSearchTool(
    override val name: String,
    override val description: String,
    private val intent: AgentIntent,
    private val provider: AgentSearchProvider,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        val queryText = term.ifBlank { call.args.values.firstOrNull().orEmpty() }
        val analyzed = QueryAnalyzer(entityIndex).analyze(queryText)
        val query = AgentQuery(
            rawText = queryText,
            normalizedText = analyzed.normalizedText,
            intents = listOf(ScoredIntent(intent, 10)),
            entities = analyzed.entities,
        )
        val hits = provider.search(query)
        if (hits.isEmpty()) {
            return AgentToolResult(
                call = call,
                summary = "$description 未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource(description, "local")),
                confidence = 0f,
            )
        }
        return AgentToolResult(
            call = call,
            summary = hits.joinToString("\n") { it.summary },
            facts = hits.flatMap { it.facts },
            sources = hits.flatMap { it.sources }.distinct(),
            confidence = hits.maxOf { it.confidence },
        )
    }
}

private class GlobalIndexTool(
    private val entityIndex: AgentEntityIndex,
) : AgentTool {
    override val name: String = "search_local_index"
    override val description: String = "本地索引查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val rawTerm = call.args["term"].orEmpty()
        val normalizedTerm = AgentAliasNormalizer.normalize(rawTerm)
        val compactTerm = AgentAliasNormalizer.compact(rawTerm)
        if (normalizedTerm.isBlank()) {
            return AgentToolResult(
                call = call,
                summary = "本地索引未命中相关数据",
                facts = emptyList(),
                sources = listOf(AgentSource("App 本地索引", "local")),
                confidence = 0f,
            )
        }
        val hits = entityIndex.entries.filter { entity ->
            val aliases = AgentAliasNormalizer.expandAliases(
                entity.displayName,
                entity.value,
                *entity.aliases.toTypedArray(),
            )
            aliases.any { alias ->
                val normalizedAlias = AgentAliasNormalizer.normalize(alias)
                val compactAlias = AgentAliasNormalizer.compact(alias)
                normalizedAlias.isNotBlank() &&
                    (
                        normalizedTerm.contains(normalizedAlias) ||
                            normalizedAlias.contains(normalizedTerm) ||
                            compactTerm.contains(compactAlias) ||
                            compactAlias.contains(compactTerm)
                        )
            }
        }.take(GlobalSearchSkill.MAX_RESULTS)
        return AgentToolResult(
            call = call,
            summary = if (hits.isEmpty()) "本地索引未命中相关数据" else hits.joinToString("；") { it.displayName },
            facts = hits.map { AgentFact(it.type, it.displayName) },
            sources = listOf(AgentSource("App 本地索引", "local")),
            confidence = if (hits.isEmpty()) 0f else 0.4f,
        )
    }
}
