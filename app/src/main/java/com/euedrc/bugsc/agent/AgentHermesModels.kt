package com.euedrc.bugsc.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class AgentSkillCard(
    val id: String,
    val title: String,
    val matchingIntents: Set<AgentIntent>,
    val workflow: String,
    val preferredTools: List<String>,
)

object AgentSkillCardProvider {
    fun defaultCards(): List<AgentSkillCard> = listOf(
        AgentSkillCard(
            id = "identity-help",
            title = "角色与能力说明",
            matchingIntents = setOf(AgentIntent.SELF_HELP),
            workflow = "说明你是应用内置助手，可以读取 App 预置资料和已开放查询接口；不要要求用户上传隐私数据，不要假装持有 SCM 登录态。",
            preferredTools = emptyList(),
        ),
        AgentSkillCard(
            id = "ship-research",
            title = "飞船资料排查",
            matchingIntents = setOf(AgentIntent.SHIP_INFO),
            workflow = "先查飞船资料；如果资料不足，区分船体参数、组件槽位、玩法建议和版本不确定信息。",
            preferredTools = listOf("search_ship"),
        ),
        AgentSkillCard(
            id = "mining-research",
            title = "矿物与采集排查",
            matchingIntents = setOf(AgentIntent.MINING),
            workflow = "先查矿物资料；回答时给出矿物名、常见地点、采集风险和需要用户补充的条件。",
            preferredTools = listOf("search_mining"),
        ),
        AgentSkillCard(
            id = "blueprint-crafting",
            title = "蓝图制作与来源排查",
            matchingIntents = setOf(AgentIntent.BLUEPRINT),
            workflow = "先查蓝图本体，再交叉查任务来源、维科洛兑换和矿物材料。资料未命中不等于不能回答，需要说明是资料未命中还是模型推断，并给下一步排查路径。",
            preferredTools = listOf("search_blueprint", "search_scm_blueprint", "search_mission", "search_wikelo", "search_mining"),
        ),
        AgentSkillCard(
            id = "mission-reward",
            title = "任务与奖励排查",
            matchingIntents = setOf(AgentIntent.MISSION),
            workflow = "先查任务资料，再交叉查蓝图和兑换资料；回答时分清任务地点、阵营、奖励和蓝图掉落。",
            preferredTools = listOf("search_mission", "search_blueprint", "search_wikelo"),
        ),
        AgentSkillCard(
            id = "wikelo-trade",
            title = "维科洛兑换排查",
            matchingIntents = setOf(AgentIntent.WIKELO),
            workflow = "先查维科洛兑换，再交叉查材料是否来自矿物或蓝图；缺资料时给出关键词修正建议。",
            preferredTools = listOf("search_wikelo", "search_mining", "search_blueprint"),
        ),
        AgentSkillCard(
            id = "market-query",
            title = "市场查询排查",
            matchingIntents = setOf(AgentIntent.MARKET),
            workflow = "先使用已开放的市场查询能力；不要声称能访问未授权登录数据，价格和库存必须标注数据来源。",
            preferredTools = listOf("search_scm_item", "search_market"),
        ),
        AgentSkillCard(
            id = "general-guide",
            title = "游戏攻略分析",
            matchingIntents = setOf(AgentIntent.GUIDE, AgentIntent.UNKNOWN),
            workflow = "问题没有明确资料库时，不要只查索引；同时查询飞船、矿物、蓝图、任务、维科洛和本地索引，把可用证据交给模型汇总。资料不足时给出可执行的排查步骤，不要伪造具体数值。",
            preferredTools = listOf("search_blueprint", "search_mission", "search_wikelo", "search_mining", "search_ship", "search_local_index"),
        ),
    )
}

data class AgentToolCall(
    val tool: String,
    val args: Map<String, String>,
    val reason: String = "",
)

data class AgentToolResult(
    val call: AgentToolCall,
    val summary: String,
    val facts: List<AgentFact>,
    val sources: List<AgentSource>,
    val confidence: Float,
    val error: String? = null,
)

interface AgentTool {
    val name: String
    val description: String
    suspend fun run(call: AgentToolCall): AgentToolResult
}

data class AgentPlan(
    val skillCards: List<AgentSkillCard>,
    val toolCalls: List<AgentToolCall>,
)

class AgentPlanner(private val skillCards: List<AgentSkillCard>) {

    fun plan(query: AgentQuery): AgentPlan {
        val cards = selectCards(query)
        val term = extractTerm(query)
        val calls = cards
            .flatMap { card ->
                card.preferredTools.map { tool ->
                    AgentToolCall(
                        tool = tool,
                        args = mapOf("term" to term),
                        reason = card.id,
                    )
                }
            }
            .distinctBy { it.tool }
        return AgentPlan(cards, calls)
    }

    private fun selectCards(query: AgentQuery): List<AgentSkillCard> {
        val intents = (query.intents.map { it.intent } + query.entities.mapNotNull { it.impliedIntent() }).toSet()
        val matched = skillCards
            .filter { card -> card.matchingIntents.any { it in intents } }
            .ifEmpty { skillCards.filter { AgentIntent.UNKNOWN in it.matchingIntents } }
        return matched.take(MAX_CARDS)
    }

    private fun ScoredEntity.impliedIntent(): AgentIntent? = when (type) {
        "blueprint" -> AgentIntent.BLUEPRINT
        "mining_element" -> AgentIntent.MINING
        "ship" -> AgentIntent.SHIP_INFO
        "wikelo_trade" -> AgentIntent.WIKELO
        else -> null
    }

    private fun extractTerm(query: AgentQuery): String {
        query.entities.firstOrNull()?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        val compact = query.normalizedText
            .replace(NOISE_WORDS, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return compact.ifBlank { query.normalizedText.ifBlank { query.rawText } }
    }

    companion object {
        private const val MAX_CARDS = 3
        private val NOISE_WORDS = Regex(
            """(请问|帮我|查一下|查询|查|搜一下|搜|就叫|叫|名称是|名字是|物品是|东西是|你|scm|蓝图|怎么做|怎么弄|如何做|如何制作|制作|材料|任务|来源|哪里来|哪里拿|哪里|在哪|获得|获取|做|弄|是什么|什么|信息|攻略)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

class AgentToolRegistry(tools: List<AgentTool>) {
    private val toolMap = tools.associateBy { it.name }

    suspend fun execute(calls: List<AgentToolCall>): List<AgentToolResult> = coroutineScope {
        calls.map { call ->
            async {
                val tool = toolMap[call.tool]
                if (tool == null) {
                    AgentToolResult(
                        call = call,
                        summary = "${call.tool} 暂不可用",
                        facts = emptyList(),
                        sources = listOf(AgentSource(call.tool, "tool")),
                        confidence = 0f,
                        error = "tool not registered",
                    )
                } else {
                    runCatching { tool.run(call) }
                        .getOrElse { error ->
                            AgentToolResult(
                                call = call,
                                summary = "${tool.description} 暂不可用",
                                facts = emptyList(),
                                sources = listOf(AgentSource(tool.description, "tool")),
                                confidence = 0f,
                                error = error.message ?: error::class.java.simpleName,
                            )
                        }
                }
            }
        }.awaitAll()
    }
}
