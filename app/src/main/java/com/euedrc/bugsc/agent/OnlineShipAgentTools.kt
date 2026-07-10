package com.euedrc.bugsc.agent

import com.euedrc.bugsc.data.ShipOnlineDataSource
import org.json.JSONArray
import org.json.JSONObject

object OnlineShipAgentTools {
    fun create(source: ShipOnlineDataSource): List<AgentTool> = listOf(
        OnlineShipSearchTool(source),
        OnlineShipDetailTool(source),
        OnlineComponentDetailTool(source),
    )
}

class OnlineShipSearchTool(
    private val source: ShipOnlineDataSource,
) : AgentTool {
    override val name = "search_online_ship"
    override val description = "线上飞船资料搜索。仅在当前构建启用线上数据源时返回结果。"
    override val parameters = listOf(
        AgentToolParameter("query", "必填：船名、英文名或中文名", required = true),
        AgentToolParameter("limit", "可选：最多返回条数，默认 5，最大 12", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val query = call.args["query"]?.trim().orEmpty()
        if (query.isBlank()) return result(call, "请输入要搜索的船名。", 0.2f)
        val limit = call.args["limit"]?.toIntOrNull()?.coerceIn(1, 12) ?: 5
        val hits = source.searchShips(query, limit)
        if (hits.isEmpty()) {
            return result(call, "暂无线上结果，或当前构建未启用线上数据源。", 0.4f)
        }
        return AgentToolResult(
            call = call,
            summary = hits.joinToString("\n") { hit ->
                val cn = hit.nameCn ?: hit.title
                val en = hit.nameEn?.let { " / $it" }.orEmpty()
                "- $cn$en (${hit.id})"
            },
            facts = hits.flatMap {
                listOf(
                    AgentFact("线上飞船ID", it.id),
                    AgentFact("线上飞船名称", it.nameCn ?: it.title),
                )
            },
            sources = listOf(AgentSource("线上飞船资料", "remote")),
            confidence = 0.75f,
        )
    }
}

class OnlineShipDetailTool(
    private val source: ShipOnlineDataSource,
) : AgentTool {
    override val name = "get_online_ship_detail"
    override val description = "线上飞船详情查询。用于挂点、导弹架、子物品等本地资产不完整的信息。"
    override val parameters = listOf(
        AgentToolParameter("id", "必填：飞船 ID，例如 rsi-polaris", required = true),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val id = call.args["id"]?.trim().orEmpty()
        if (id.isBlank()) return result(call, "请输入飞船 ID。", 0.2f)
        val detail = source.getShipDetail(id)
            ?: return result(call, "未找到线上详情，或当前构建未启用线上数据源。", 0.4f)
        val name = detail.nameCn ?: detail.title
        val en = detail.nameEn?.let { " / $it" }.orEmpty()
        val formatted = OnlineDetailFormatter.ship(detail.rawJson)
        return AgentToolResult(
            call = call,
            summary = "$name$en (${detail.id})\n${formatted.summary}",
            facts = listOf(
                AgentFact("线上飞船ID", detail.id),
                AgentFact("线上飞船名称", name),
            ) + formatted.facts,
            sources = listOf(AgentSource("线上飞船详情", "remote")),
            confidence = 0.85f,
        )
    }
}

class OnlineComponentDetailTool(
    private val source: ShipOnlineDataSource,
) : AgentTool {
    override val name = "get_online_component_detail"
    override val description = "线上配件或物品详情查询。type 使用挂点返回的 related.resource，id 使用 related.id。"
    override val parameters = listOf(
        AgentToolParameter("type", "必填：资源类型，例如 missiles、missile_racks、shields", required = true),
        AgentToolParameter("id", "必填：配件或物品 ID", required = true),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val type = call.args["type"]?.trim().orEmpty()
        val id = call.args["id"]?.trim().orEmpty()
        if (type.isBlank() || id.isBlank()) return result(call, "请输入资源类型和物品 ID。", 0.2f)
        val detail = source.getComponentDetail(type, id)
            ?: return result(call, "未找到线上物品详情，或当前构建未启用线上数据源。", 0.4f)
        val formatted = OnlineDetailFormatter.component(detail.rawJson)
        return AgentToolResult(
            call = call,
            summary = "${detail.type}/${detail.id}\n${formatted.summary}",
            facts = listOf(
                AgentFact("线上资源类型", detail.type),
                AgentFact("线上物品ID", detail.id),
            ) + formatted.facts,
            sources = listOf(AgentSource("线上物品详情", "remote")),
            confidence = 0.85f,
        )
    }
}

private data class FormattedOnlineDetail(
    val summary: String,
    val facts: List<AgentFact>,
)

private object OnlineDetailFormatter {
    fun ship(rawJson: String): FormattedOnlineDetail {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return FormattedOnlineDetail("详情数据格式异常。", emptyList())
        val identity = listOfNotNull(
            pair("制造商", root.firstString("manufacturer_cn", "manufacturer_en")),
            pair("定位", root.firstString("role")),
            pair("货舱", root.valueText("cargo")),
            pair("尺寸", root.dimensionsText()),
            pair("商店价格", root.valueText("store_price")),
            pair("版本", root.firstString("version")),
        )
        val hardpoints = root.optJSONObject("hardpoints")
        val sections = hardpoints?.keys()?.asSequence().orEmpty().mapNotNull { key ->
            val section = hardpoints?.optJSONObject(key) ?: return@mapNotNull null
            val items = section.optJSONArray("items") ?: return@mapNotNull null
            if (items.length() == 0) return@mapNotNull null
            val label = section.optString("label").ifBlank { key }
            val nodes = (0 until minOf(items.length(), MAX_NODES_PER_SECTION))
                .mapNotNull(items::optJSONObject)
                .joinToString("；") { formatNode(it, 0) }
            val remainder = (items.length() - MAX_NODES_PER_SECTION).coerceAtLeast(0)
            "$label(${items.length()}组)：$nodes${if (remainder > 0) "；另有${remainder}组" else ""}"
        }.toList()
        val related = mutableListOf<String>()
        hardpoints?.let { collectRelated(it, related) }
        val summary = buildString {
            if (identity.isNotEmpty()) appendLine(identity.joinToString("；") { "${it.first}：${it.second}" })
            if (sections.isNotEmpty()) {
                appendLine("挂点配装：")
                sections.forEach { appendLine("- $it") }
            } else {
                append("未返回挂点配装。")
            }
        }.trim().take(MAX_SUMMARY_CHARS)
        val facts = buildList {
            identity.take(5).forEach { (label, value) -> add(AgentFact(label, value)) }
            if (sections.isNotEmpty()) add(AgentFact("挂点类别", sections.joinToString(" | ").take(FACT_VALUE_CHARS)))
            if (related.isNotEmpty()) add(AgentFact("可继续查询的关联物品", related.distinct().take(20).joinToString("；")))
        }
        return FormattedOnlineDetail(summary, facts)
    }

    fun component(rawJson: String): FormattedOnlineDetail {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return FormattedOnlineDetail("详情数据格式异常。", emptyList())
        val name = root.firstString("name_cn", "NameCN", "name_en", "NameEN", "id", "ID")
        val preferredKeys = listOf(
            "manufacturer_cn", "manufacturer_en", "manufacturer", "type", "size", "grade",
            "description_cn", "description_en", "stats", "damage", "speed", "range", "health",
        )
        val details = preferredKeys.mapNotNull { key ->
            if (!root.has(key) || root.isNull(key)) return@mapNotNull null
            keyLabel(key) to compactValue(root.opt(key), 0)
        }.filter { it.second.isNotBlank() }
        val fallback = if (details.isEmpty()) {
            root.keys().asSequence()
                .filterNot { it in setOf("id", "ID", "name_cn", "NameCN", "name_en", "NameEN") }
                .take(12)
                .map { key -> keyLabel(key) to compactValue(root.opt(key), 0) }
                .filter { it.second.isNotBlank() }
                .toList()
        } else details
        val summary = buildString {
            if (name.isNotBlank()) appendLine("名称：$name")
            fallback.forEach { (label, value) -> appendLine("$label：$value") }
        }.trim().take(MAX_SUMMARY_CHARS)
        return FormattedOnlineDetail(
            summary = summary.ifBlank { "已取得物品详情。" },
            facts = fallback.take(6).map { (label, value) -> AgentFact(label, value.take(FACT_VALUE_CHARS)) },
        )
    }

    private fun formatNode(node: JSONObject, depth: Int): String {
        val name = node.firstString("name_cn", "name_en", "item_id", "port_name").ifBlank { "未命名挂点" }
        val tags = listOfNotNull(
            node.opt("size")?.takeUnless { it == JSONObject.NULL }?.let { "S$it" },
            node.optInt("count", 1).takeIf { it > 1 }?.let { "×$it" },
            node.optString("controlled_by").takeIf(String::isNotBlank),
            if (node.optBoolean("locked")) "锁定" else null,
            node.optJSONObject("related")?.let { related ->
                val resource = related.optString("resource")
                val id = related.optString("id")
                if (resource.isNotBlank() && id.isNotBlank()) "$resource/$id" else null
            },
        )
        val children = node.optJSONArray("children")
        val childText = if (depth < 1 && children != null && children.length() > 0) {
            (0 until minOf(children.length(), MAX_CHILDREN_PER_NODE))
                .mapNotNull(children::optJSONObject)
                .joinToString("、", prefix = " -> ") { formatNode(it, depth + 1) }
        } else ""
        return "$name${tags.joinToString(prefix = " [", postfix = "]").takeIf { tags.isNotEmpty() }.orEmpty()}$childText"
    }

    private fun collectRelated(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("related")?.let { related ->
                    val resource = related.optString("resource")
                    val id = related.optString("id")
                    if (resource.isNotBlank() && id.isNotBlank()) output += "$resource/$id"
                }
                value.keys().forEach { key -> collectRelated(value.opt(key), output) }
            }
            is JSONArray -> (0 until value.length()).forEach { collectRelated(value.opt(it), output) }
        }
    }

    private fun JSONObject.dimensionsText(): String? {
        val dimensions = optJSONObject("dimensions")
        val length = valueText("length") ?: dimensions?.valueText("length")
        val beam = valueText("beam") ?: dimensions?.valueText("beam")
        val height = valueText("height") ?: dimensions?.valueText("height")
        return listOfNotNull(length, beam, height).takeIf { it.isNotEmpty() }?.joinToString(" × ") { "$it m" }
    }

    private fun JSONObject.firstString(vararg keys: String): String =
        keys.asSequence().map(::optString).firstOrNull(String::isNotBlank).orEmpty()

    private fun JSONObject.valueText(key: String): String? =
        if (!has(key) || isNull(key)) null else compactValue(opt(key), 0).takeIf(String::isNotBlank)

    private fun compactValue(value: Any?, depth: Int): String {
        val formatted = when (value) {
            null, JSONObject.NULL -> ""
            is JSONObject -> {
                if (depth >= 2) {
                    "${value.length()} 项"
                } else {
                    value.keys().asSequence().take(16).joinToString("；") { key ->
                        "${keyLabel(key)}=${compactValue(value.opt(key), depth + 1)}"
                    }
                }
            }
            is JSONArray -> {
                if (depth >= 2) {
                    "${value.length()} 项"
                } else {
                    (0 until minOf(value.length(), 12)).joinToString("；") {
                        compactValue(value.opt(it), depth + 1)
                    }
                }
            }
            else -> value.toString()
        }
        return formatted.take(FACT_VALUE_CHARS)
    }

    private fun keyLabel(key: String): String = when (key) {
        "manufacturer_cn", "manufacturer_en", "manufacturer" -> "制造商"
        "type" -> "类型"
        "size" -> "尺寸"
        "grade" -> "等级"
        "description_cn", "description_en" -> "说明"
        "stats" -> "参数"
        "damage" -> "伤害"
        "speed" -> "速度"
        "range" -> "射程"
        "health" -> "耐久"
        else -> key
    }

    private fun pair(label: String, value: String?): Pair<String, String>? =
        value?.takeIf(String::isNotBlank)?.let { label to it }

    private const val MAX_NODES_PER_SECTION = 6
    private const val MAX_CHILDREN_PER_NODE = 8
    private const val MAX_SUMMARY_CHARS = 6_000
    private const val FACT_VALUE_CHARS = 2_000
}

private fun result(call: AgentToolCall, summary: String, confidence: Float): AgentToolResult =
    AgentToolResult(
        call = call,
        summary = summary,
        facts = emptyList(),
        sources = listOf(AgentSource("线上飞船资料", "remote")),
        confidence = confidence,
    )
