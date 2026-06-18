package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.MarketOrder
import com.euedrc.bugsc.market.ScmMarketClient
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.MarketPublishClient
import com.euedrc.bugsc.market.publish.OwnMarketOrder
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishOrderPage
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import com.euedrc.bugsc.market.transaction.TransactionClient
import com.euedrc.bugsc.market.transaction.TransactionPage
import com.euedrc.bugsc.market.transaction.TransactionRecord
import com.euedrc.bugsc.scm.ScmClient
import com.euedrc.bugsc.scm.ScmAuthStore
import com.euedrc.bugsc.scm.ScmResult
import com.euedrc.bugsc.scm.SignInSummary
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder

interface ScmAgentGateway {
    fun request(path: String): JSONObject
    fun searchItems(keyword: String): List<ItemSearchResult>
    fun searchOrders(keyword: String): List<MarketOrder>
    fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> =
        creatorType?.let { type -> searchOrders(keyword).filter { it.creatorType == type } } ?: searchOrders(keyword)
    fun ownOrders(pageNo: Int, pageSize: Int = 20, creatorType: PublishCreatorType? = null): PublishOrderPage =
        PublishOrderPage(emptyList(), 0)
    fun transactionPage(pageNo: Int, pageSize: Int = 20): TransactionPage =
        TransactionPage(emptyList(), 0)
    fun signInSummary(): SignInSummary? = null
    fun signIn(): ScmResult = ScmResult(false, "签到暂不可用", -1)
    fun updateOwnOrder(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    ) = Unit
    fun setOwnOrderStatus(orderNumber: String, status: PublishOrderStatus) = Unit
    fun deleteOwnOrder(orderNumber: String) = Unit
    fun quantityAdd(orderNumber: String) = Unit
}

class DefaultScmAgentGateway(
    private val publishClient: MarketPublishClient = MarketPublishClient(),
    private val marketClient: ScmMarketClient = ScmMarketClient(),
    private val transactionClient: TransactionClient = TransactionClient(),
) : ScmAgentGateway {
    override fun request(path: String): JSONObject =
        JSONObject(ScmAuthStore.api().request("GET", path).body)

    override fun searchItems(keyword: String): List<ItemSearchResult> =
        publishClient.searchItems(keyword)

    override fun searchOrders(keyword: String): List<MarketOrder> =
        marketClient.fetchPage(creatorType = 1, pageNo = 1, pageSize = 5, keyword = keyword).list +
            marketClient.fetchPage(creatorType = 0, pageNo = 1, pageSize = 5, keyword = keyword).list

    override fun searchOrders(keyword: String, creatorType: Int?): List<MarketOrder> =
        if (creatorType == null) {
            searchOrders(keyword)
        } else {
            marketClient.fetchPage(creatorType = creatorType, pageNo = 1, pageSize = 5, keyword = keyword).list
        }

    override fun ownOrders(pageNo: Int, pageSize: Int, creatorType: PublishCreatorType?): PublishOrderPage =
        publishClient.ownOrders(pageNo = pageNo, pageSize = pageSize, creatorType = creatorType)

    override fun transactionPage(pageNo: Int, pageSize: Int): TransactionPage =
        transactionClient.page(pageNo = pageNo, pageSize = pageSize)

    override fun signInSummary(): SignInSummary? =
        ScmClient.signInSummary()

    override fun signIn(): ScmResult =
        ScmClient.signIn()

    override fun updateOwnOrder(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    ) {
        publishClient.updateOrder(orderNumber, unitPrice, remainingQuantity, status, expireTime, quality)
    }

    override fun setOwnOrderStatus(orderNumber: String, status: PublishOrderStatus) {
        publishClient.setOrderStatus(orderNumber, status)
    }

    override fun deleteOwnOrder(orderNumber: String) {
        publishClient.deleteOrder(orderNumber)
    }

    override fun quantityAdd(orderNumber: String) {
        publishClient.quantityAdd(orderNumber)
    }
}

object ScmAgentTools {
    fun create(
        gateway: ScmAgentGateway = DefaultScmAgentGateway(),
        entityIndex: AgentEntityIndex = AgentEntityIndex(),
    ): List<AgentTool> = listOf(
        ScmBlueprintSearchTool(gateway, entityIndex),
        ScmItemSearchTool(gateway, entityIndex),
        ScmMyOrdersTool(gateway, isLoggedIn = { ScmAuthStore.isLoggedIn }),
        ScmManageMyOrderTool(gateway, isLoggedIn = { ScmAuthStore.isLoggedIn }),
        ScmMarketActivityTool(
            gateway = gateway,
            isLoggedIn = { ScmAuthStore.isLoggedIn },
            currentUserId = { ScmAuthStore.session().userId },
        ),
        ScmSignInTool(gateway, isLoggedIn = { ScmAuthStore.isLoggedIn }),
        ScmMarketOrderSearchTool(gateway, entityIndex),
    )
}

class ScmMyOrdersTool(
    private val gateway: ScmAgentGateway,
    private val isLoggedIn: () -> Boolean,
) : AgentTool {
    override val name: String = "list_my_orders"
    override val description: String = "查询当前登录用户自己的 SCM 挂单"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("side", "可选：sell 查询我的出售挂单；buy 查询我的求购挂单；不确定时留空", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        if (!isLoggedIn()) {
            return AgentToolResult(
                call = call,
                summary = "需要先登录 SCM，才能查看我的订单。",
                facts = emptyList(),
                sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                confidence = 0.5f,
            )
        }
        val creatorType = when (call.args["side"].orEmpty().trim().lowercase()) {
            "sell", "出售", "selling" -> PublishCreatorType.SELL
            "buy", "求购", "buying" -> PublishCreatorType.BUY
            else -> null
        }
        val page = gateway.ownOrders(pageNo = 1, pageSize = 5, creatorType = creatorType)
        if (page.items.isEmpty()) {
            return AgentToolResult(
                call = call,
                summary = "你当前没有 SCM 挂单。",
                facts = emptyList(),
                sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                confidence = 0.7f,
            )
        }
        val visible = page.items.take(5)
        return AgentToolResult(
            call = call,
            summary = visible.joinToString("\n") { it.formatMyOrder() },
            facts = visible.flatMap {
                listOf(
                    AgentFact("我的订单", it.orderNumber),
                    AgentFact("类型", it.creatorType.label),
                    AgentFact("物品", it.itemName),
                    AgentFact("单价", "${formatPrice(it.unitPrice)} aUEC"),
                    AgentFact("数量", it.remainingQuantity.toString()),
                    AgentFact("状态", it.status.label),
                )
            },
            sources = listOf(AgentSource("SCM 我的挂单", "remote")),
            confidence = 0.82f,
        )
    }

    private fun OwnMarketOrder.formatMyOrder(): String = buildString {
        append(orderNumber)
        append("，")
        append(creatorType.label)
        append(" ")
        append(itemName)
        append("，")
        append(formatPrice(unitPrice))
        append(" aUEC ×")
        append(remainingQuantity)
        append("，")
        append(status.label)
    }
}

class ScmManageMyOrderTool(
    private val gateway: ScmAgentGateway,
    private val isLoggedIn: () -> Boolean,
) : AgentTool {
    override val name: String = "manage_my_order"
    override val description: String = "管理当前登录用户自己的 SCM 挂单：编辑、上架、下架、删除或补数量"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("action", "必填：edit、hide、show、delete、quantity_add", required = true),
        AgentToolParameter("orderNumber", "必填：要操作的订单编号", required = true),
        AgentToolParameter("unitPrice", "编辑时可选：新的单价", required = false),
        AgentToolParameter("remainingQuantity", "编辑时可选：新的剩余数量", required = false),
        AgentToolParameter("status", "编辑时可选：visible/show 上架；hidden/hide 下架", required = false),
        AgentToolParameter("expireTime", "编辑时可选：7、14、permanent/永久；默认 7 天", required = false),
        AgentToolParameter("quality", "编辑时可选：品质 0-1000；不填则不提交品质", required = false),
        AgentToolParameter("confirm", "删除时必须为 true/yes/确认，防止误删", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        if (!isLoggedIn()) {
            return AgentToolResult(
                call = call,
                summary = "需要先登录 SCM，才能修改我的订单。",
                facts = emptyList(),
                sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                confidence = 0.5f,
            )
        }
        val action = normalizeAction(call.args["action"].orEmpty())
            ?: return problem(call, "请说明要执行的订单操作：edit、hide、show、delete 或 quantity_add。")
        val orderNumber = call.args["orderNumber"].orEmpty().trim()
        if (orderNumber.isBlank()) {
            return askForOrderNumber(call)
        }
        val order = findOwnOrder(orderNumber)
            ?: return problem(call, "没有在我的 SCM 挂单中找到订单 $orderNumber，请检查订单编号。")
        return when (action) {
            "hide" -> {
                gateway.setOwnOrderStatus(order.orderNumber, PublishOrderStatus.HIDDEN)
                success(call, "已下架订单 ${order.orderNumber}：${order.itemName}", order, "下架")
            }
            "show" -> {
                gateway.setOwnOrderStatus(order.orderNumber, PublishOrderStatus.VISIBLE)
                success(call, "已上架订单 ${order.orderNumber}：${order.itemName}", order, "上架")
            }
            "delete" -> delete(call, order)
            "quantity_add" -> {
                gateway.quantityAdd(order.orderNumber)
                success(call, "已为订单 ${order.orderNumber} 补数量：${order.itemName}", order, "补数量")
            }
            else -> edit(call, order)
        }
    }

    private fun edit(call: AgentToolCall, order: OwnMarketOrder): AgentToolResult {
        val nextPrice = call.args["unitPrice"]?.trim()?.takeIf(String::isNotBlank)?.toBigDecimalOrNull()
            ?: order.unitPrice
        val nextQty = call.args["remainingQuantity"]?.trim()?.takeIf(String::isNotBlank)?.toIntOrNull()
            ?: call.args["quantity"]?.trim()?.takeIf(String::isNotBlank)?.toIntOrNull()
            ?: order.remainingQuantity
        val nextStatus = call.args["status"]?.let(::normalizeStatus) ?: order.status
        val nextExpire = normalizeExpire(call.args["expireTime"].orEmpty())
        val quality = call.args["quality"]?.trim()?.takeIf(String::isNotBlank)?.toIntOrNull()
        if (nextPrice <= BigDecimal.ZERO) return problem(call, "单价必须大于 0。")
        if (nextQty <= 0) return problem(call, "数量必须大于 0。")
        if (call.args["quality"]?.isNotBlank() == true && (quality == null || quality !in 0..1000)) {
            return problem(call, "品质必须在 0 到 1000 之间。")
        }
        gateway.updateOwnOrder(
            orderNumber = order.orderNumber,
            unitPrice = nextPrice,
            remainingQuantity = nextQty,
            status = nextStatus,
            expireTime = nextExpire,
            quality = quality,
        )
        return AgentToolResult(
            call = call,
            summary = buildString {
                append("已更新订单 ${order.orderNumber}：${order.itemName}，")
                append(formatPrice(nextPrice))
                append(" aUEC ×")
                append(nextQty)
                append("，")
                append(nextStatus.label)
                append("，有效期 ")
                append(nextExpire.label)
                if (call.args["expireTime"].isNullOrBlank()) append("（未指定有效期，按默认 7 天提交）")
            },
            facts = orderFacts(order) + listOf(
                AgentFact("操作", "编辑"),
                AgentFact("新单价", "${formatPrice(nextPrice)} aUEC"),
                AgentFact("新数量", nextQty.toString()),
                AgentFact("新状态", nextStatus.label),
                AgentFact("有效期", nextExpire.label),
            ),
            sources = listOf(AgentSource("SCM 我的挂单", "remote")),
            confidence = 0.82f,
        )
    }

    private fun delete(call: AgentToolCall, order: OwnMarketOrder): AgentToolResult {
        if (!isConfirmed(call.args["confirm"].orEmpty())) {
            return AgentToolResult(
                call = call,
                summary = "删除订单 ${order.orderNumber} 是不可恢复操作。请明确确认删除后再执行，例如：确认删除订单 ${order.orderNumber}。",
                facts = orderFacts(order) + AgentFact("需要确认", "是"),
                sources = listOf(AgentSource("SCM 我的挂单", "remote")),
                confidence = 0.62f,
            )
        }
        gateway.deleteOwnOrder(order.orderNumber)
        return success(call, "已删除订单 ${order.orderNumber}：${order.itemName}", order, "删除")
    }

    private fun askForOrderNumber(call: AgentToolCall): AgentToolResult {
        val orders = gateway.ownOrders(pageNo = 1, pageSize = 5).items
        return AgentToolResult(
            call = call,
            summary = buildString {
                append("请提供要操作的订单编号。")
                if (orders.isNotEmpty()) {
                    appendLine()
                    orders.take(5).forEach { appendLine(it.formatManageLine()) }
                }
            }.trim(),
            facts = orders.take(5).map { AgentFact("我的订单", it.orderNumber) },
            sources = listOf(AgentSource("SCM 我的挂单", "remote")),
            confidence = 0.62f,
        )
    }

    private fun findOwnOrder(orderNumber: String): OwnMarketOrder? =
        gateway.ownOrders(pageNo = 1, pageSize = 100).items
            .firstOrNull { it.orderNumber.equals(orderNumber, ignoreCase = true) }

    private fun success(
        call: AgentToolCall,
        summary: String,
        order: OwnMarketOrder,
        action: String,
    ): AgentToolResult = AgentToolResult(
        call = call,
        summary = summary,
        facts = orderFacts(order) + AgentFact("操作", action),
        sources = listOf(AgentSource("SCM 我的挂单", "remote")),
        confidence = 0.84f,
    )

    private fun problem(call: AgentToolCall, summary: String): AgentToolResult =
        AgentToolResult(
            call = call,
            summary = summary,
            facts = emptyList(),
            sources = listOf(AgentSource("SCM 我的挂单", "remote")),
            confidence = 0.25f,
            error = summary,
        )

    private fun orderFacts(order: OwnMarketOrder): List<AgentFact> = listOf(
        AgentFact("订单", order.orderNumber),
        AgentFact("物品", order.itemName),
        AgentFact("类型", order.creatorType.label),
        AgentFact("当前单价", "${formatPrice(order.unitPrice)} aUEC"),
        AgentFact("当前数量", order.remainingQuantity.toString()),
        AgentFact("当前状态", order.status.label),
    )

    private fun OwnMarketOrder.formatManageLine(): String =
        "${orderNumber}，${creatorType.label} ${itemName}，${formatPrice(unitPrice)} aUEC ×$remainingQuantity，${status.label}"

    private fun normalizeAction(raw: String): String? {
        val value = AgentAliasNormalizer.compact(raw)
        return when {
            value in setOf("hide", "hidden", "off", "下架", "隐藏", "暂停") || value.contains("下架") -> "hide"
            value in setOf("show", "visible", "on", "上架", "恢复") || value.contains("上架") -> "show"
            value in setOf("delete", "remove", "删除", "删掉", "移除") || value.contains("删除") -> "delete"
            value in setOf("quantityadd", "addquantity", "补数量", "补货") || value.contains("补数量") || value.contains("补货") -> "quantity_add"
            value in setOf("edit", "update", "修改", "编辑") || value.contains("改") || value.contains("编辑") || value.contains("修改") -> "edit"
            else -> null
        }
    }

    private fun normalizeStatus(raw: String): PublishOrderStatus {
        val value = AgentAliasNormalizer.compact(raw)
        return when {
            value.contains("hide") || value.contains("hidden") || value.contains("下架") || value.contains("隐藏") -> PublishOrderStatus.HIDDEN
            else -> PublishOrderStatus.VISIBLE
        }
    }

    private fun normalizeExpire(raw: String): PublishExpireTime {
        val value = AgentAliasNormalizer.compact(raw)
        return when {
            value.contains("14") || value.contains("十四") || value.contains("两周") -> PublishExpireTime.FOURTEEN_DAYS
            value.contains("永久") || value.contains("permanent") || value.contains("forever") -> PublishExpireTime.PERMANENT
            else -> PublishExpireTime.SEVEN_DAYS
        }
    }

    private fun isConfirmed(raw: String): Boolean {
        val value = AgentAliasNormalizer.compact(raw)
        return value in setOf("true", "yes", "y", "确认", "确定", "delete", "删除")
    }
}

class ScmMarketActivityTool(
    private val gateway: ScmAgentGateway,
    private val isLoggedIn: () -> Boolean,
    private val currentUserId: () -> Long,
) : AgentTool {
    override val name: String = "list_my_market_activity"
    override val description: String = "查询我的 SCM 挂单和交易记录"
    override val parameters: List<AgentToolParameter> = emptyList()

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        if (!isLoggedIn()) {
            return AgentToolResult(
                call = call,
                summary = "需要先登录 SCM，才能查看我的交易和挂单。",
                facts = emptyList(),
                sources = listOf(AgentSource("SCM 我的市场活动", "remote")),
                confidence = 0.5f,
            )
        }
        val orders = gateway.ownOrders(pageNo = 1, pageSize = 5).items
        val transactions = gateway.transactionPage(pageNo = 1, pageSize = 5).items
        val userId = currentUserId()
        return AgentToolResult(
            call = call,
            summary = buildString {
                appendLine("我的挂单")
                if (orders.isEmpty()) {
                    appendLine("暂无挂单")
                } else {
                    orders.take(5).forEach { appendLine(it.formatOwnOrder()) }
                }
                appendLine()
                appendLine("我的交易")
                if (transactions.isEmpty()) {
                    appendLine("暂无交易记录")
                } else {
                    transactions.take(5).forEach { appendLine(it.formatMyTransaction(userId)) }
                }
            }.trim(),
            facts = listOf(
                AgentFact("我的挂单数量", orders.size.toString()),
                AgentFact("我的交易数量", transactions.size.toString()),
            ) + orders.take(5).map { AgentFact("我的挂单", it.orderNumber) } +
                transactions.take(5).map { AgentFact("我的交易", it.transactionNumber) },
            sources = listOf(AgentSource("SCM 我的市场活动", "remote")),
            confidence = 0.82f,
        )
    }

    private fun OwnMarketOrder.formatOwnOrder(): String = buildString {
        append(orderNumber)
        append("，")
        append(creatorType.label)
        append(" ")
        append(itemName)
        append("，")
        append(formatPrice(unitPrice))
        append(" aUEC ×")
        append(remainingQuantity)
        append("，")
        append(status.label)
    }

    private fun TransactionRecord.formatMyTransaction(userId: Long): String {
        val sellOrder = creatorType == 1
        val buyerId = if (sellOrder) tradingerId else orderOwnerId
        val buyerName = if (sellOrder) tradingerName else orderOwnerName
        val sellerId = if (sellOrder) orderOwnerId else tradingerId
        val sellerName = if (sellOrder) orderOwnerName else tradingerName
        val role = when (userId) {
            buyerId -> "我买入"
            sellerId -> "我卖出"
            else -> if (sellOrder) "出售交易" else "求购交易"
        }
        val counterparty = when (role) {
            "我买入" -> "卖家 ${sellerName.ifBlank { sellerId.toString() }}"
            "我卖出" -> "买家 ${buyerName.ifBlank { buyerId.toString() }}"
            else -> listOf(
                "卖家 ${sellerName.ifBlank { sellerId.toString() }}",
                "买家 ${buyerName.ifBlank { buyerId.toString() }}",
            ).joinToString("，")
        }
        val location = transactionLocationName.ifBlank { locationName }
        return buildString {
            append(transactionNumber)
            append("，")
            append(role)
            append(" ")
            append(itemsName.ifBlank { "交易订单" })
            append(" ×")
            append(number)
            append("，总额 ")
            append(formatPrice(amount))
            append(" aUEC")
            append("，")
            append(counterparty)
            if (location.isNotBlank()) append("，地点 ").append(location)
            append("，")
            append(transactionStatusLabel)
        }
    }
}

class ScmSignInTool(
    private val gateway: ScmAgentGateway,
    private val isLoggedIn: () -> Boolean,
) : AgentTool {
    override val name: String = "scm_sign_in"
    override val description: String = "执行或查询 SCM 每日签到"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("action", "sign 执行签到；status 只查询签到状态", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        if (!isLoggedIn()) {
            return AgentToolResult(
                call = call,
                summary = "需要先登录 SCM，才能签到。",
                facts = emptyList(),
                sources = listOf(AgentSource("SCM 签到", "remote")),
                confidence = 0.5f,
            )
        }
        val action = call.args["action"].orEmpty().ifBlank { "sign" }.lowercase()
        val before = gateway.signInSummary()
        if (action == "status") {
            return signInResult(call, before, prefix = null, confidence = 0.78f)
        }
        if (before?.todaySignIn == true) {
            return signInResult(call, before, prefix = "今日已签到", confidence = 0.82f)
        }
        val result = gateway.signIn()
        if (!result.success) {
            return AgentToolResult(
                call = call,
                summary = result.msg.ifBlank { "签到失败" },
                facts = listOf(AgentFact("签到结果", "失败")),
                sources = listOf(AgentSource("SCM 签到", "remote")),
                confidence = 0.45f,
                error = result.msg.ifBlank { "sign in failed" },
            )
        }
        val after = gateway.signInSummary() ?: before
        return signInResult(call, after, prefix = "签到成功", confidence = 0.82f)
    }

    private fun signInResult(
        call: AgentToolCall,
        summary: SignInSummary?,
        prefix: String?,
        confidence: Float,
    ): AgentToolResult {
        val status = summary?.let {
            "连续签到 ${it.continuousDay} 天，累计 ${it.totalDay} 天，今日${if (it.todaySignIn) "已签到" else "未签到"}"
        }.orEmpty()
        return AgentToolResult(
            call = call,
            summary = listOfNotNull(prefix, status.ifBlank { null }).joinToString("，").ifBlank { prefix ?: "签到信息暂不可用" },
            facts = buildList {
                prefix?.let { add(AgentFact("签到结果", if (it.contains("成功") || it.contains("已签到")) "成功" else it)) }
                summary?.let {
                    add(AgentFact("连续签到", "${it.continuousDay} 天"))
                    add(AgentFact("累计签到", "${it.totalDay} 天"))
                    add(AgentFact("今日是否已签到", if (it.todaySignIn) "是" else "否"))
                }
            },
            sources = listOf(AgentSource("SCM 签到", "remote")),
            confidence = confidence,
        )
    }
}

class ScmBlueprintSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_scm_blueprint"
    override val description: String = "SCM 蓝图查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 蓝图未命中")
        val list = ScmSearchTermExpander.expand(term, entityIndex)
            .asSequence()
            .map { candidate ->
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                gateway.request("/product/blueprint/page?pageNo=1&pageSize=5&keyword=$encoded")
                    .optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: JSONArray()
            }
            .firstOrNull { it.length() > 0 }
            ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        if (items.isEmpty()) return noResult(call, "SCM 蓝图未命中")
        val summaries = items.take(3).map { it.blueprintDisplayName() }
        val facts = items.take(3).flatMap { item ->
            listOfNotNull(
                AgentFact("蓝图", item.blueprintDisplayName()),
                item.optString("categoryName").takeIf(String::isNotBlank)?.let { AgentFact("分类", it) },
                item.optInt("craftTimeSeconds").takeIf { it > 0 }?.let { AgentFact("制作时间", "${it}s") },
                item.optInt("rewardMissionCount").takeIf { it > 0 }?.let { AgentFact("来源任务数", it.toString()) },
            )
        }
        return AgentToolResult(
            call = call,
            summary = summaries.joinToString("\n"),
            facts = facts,
            sources = listOf(AgentSource("SCM 蓝图 API", "remote")),
            confidence = 0.78f,
        )
    }
}

class ScmItemSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_scm_item"
    override val description: String = "SCM 物品查询"

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["term"].orEmpty().trim()
        if (term.isBlank()) return noResult(call, "SCM 物品未命中")
        val items = ScmSearchTermExpander.expand(term, entityIndex)
            .asSequence()
            .map { gateway.searchItems(it) }
            .firstOrNull { it.isNotEmpty() }
            ?.take(5)
            .orEmpty()
        if (items.isEmpty()) return noResult(call, "SCM 物品未命中")
        return AgentToolResult(
            call = call,
            summary = items.take(3).joinToString("\n") { it.displayName() },
            facts = items.take(3).flatMap {
                listOf(
                    AgentFact("物品", it.displayName()),
                    AgentFact("物品ID", it.id.toString()),
                )
            },
            sources = listOf(AgentSource("SCM 物品 API", "remote")),
            confidence = 0.76f,
        )
    }
}

class ScmMarketOrderSearchTool(
    private val gateway: ScmAgentGateway,
    private val entityIndex: AgentEntityIndex = AgentEntityIndex(),
) : AgentTool {
    override val name: String = "search_market"
    override val description: String = "SCM 市场订单查询"
    override val parameters: List<AgentToolParameter> = listOf(
        AgentToolParameter("query", "可选：要查询的物品名或关键词；为空时返回当前市场订单列表", required = false),
        AgentToolParameter("side", "可选：sell 查询出售挂单；buy 查询求购挂单；不确定时留空", required = false),
    )

    override suspend fun run(call: AgentToolCall): AgentToolResult {
        val term = call.args["query"].orEmpty().ifBlank { call.args["term"].orEmpty() }.trim()
        val creatorType = when (call.args["side"].orEmpty().trim().lowercase()) {
            "sell", "出售", "selling" -> 1
            "buy", "求购", "buying" -> 0
            else -> null
        }
        val orders = if (term.isBlank()) {
            gateway.searchOrders("", creatorType)
                .filter { it.remainingQuantity > 0 }
                .sortedWith(compareBy<MarketOrder> { it.creatorType }.thenBy { it.unitPrice })
                .take(5)
        } else {
            ScmSearchTermExpander.expand(term, entityIndex)
                .asSequence()
                .map { candidate ->
                    gateway.searchOrders(candidate, creatorType)
                        .filter { it.remainingQuantity > 0 }
                        .sortedBy { it.unitPrice }
                        .take(5)
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        if (orders.isEmpty()) {
            val target = term.ifBlank {
                when (creatorType) {
                    1 -> "当前出售挂单"
                    0 -> "当前求购挂单"
                    else -> "当前市场订单"
                }
            }
            return noResult(call, "没有查到“$target”的 SCM 市场挂单。")
        }
        val visibleOrders = orders.take(5)
        val participantLabel = when {
            visibleOrders.all { it.creatorType == 1 } -> "卖家"
            visibleOrders.all { it.creatorType == 0 } -> "买家"
            else -> "交易方"
        }
        val actionLabel = when (participantLabel) {
            "卖家" -> "在卖"
            "买家" -> "在收"
            else -> "挂单"
        }
        val participantCount = visibleOrders
            .map { it.nickname.ifBlank { it.creatorId.toString() } }
            .distinct()
            .size
        val summaryHeader = "当前有 $participantCount 个$participantLabel$actionLabel"
        return AgentToolResult(
            call = call,
            summary = (listOf(summaryHeader) + visibleOrders.map {
                buildString {
                    append(it.itemName)
                    append("，")
                    append(if (it.creatorType == 1) "出售" else "求购")
                    append(" ")
                    append(formatPrice(it.unitPrice))
                    append(" aUEC ×")
                    append(it.remainingQuantity)
                    it.nickname.takeIf(String::isNotBlank)?.let { nickname ->
                        append("，")
                        append(if (it.creatorType == 1) "卖家 " else "买家 ")
                        append(nickname)
                    }
                    it.locationName.takeIf(String::isNotBlank)?.let { location -> append("，地点 ").append(location) }
                }
            }).joinToString("\n"),
            facts = listOf(
                AgentFact("${participantLabel}数量", participantCount.toString()),
            ) + visibleOrders.flatMap {
                listOfNotNull(
                    AgentFact("订单", it.orderNumber),
                    AgentFact("物品", it.itemName),
                    AgentFact("单价", "${formatPrice(it.unitPrice)} aUEC"),
                    AgentFact("数量", it.remainingQuantity.toString()),
                    it.nickname.takeIf(String::isNotBlank)?.let { nickname ->
                        AgentFact(if (it.creatorType == 1) "卖家" else "买家", nickname)
                    },
                    it.locationName.takeIf(String::isNotBlank)?.let { location -> AgentFact("地点", location) },
                )
            },
            sources = listOf(AgentSource("SCM 市场 API", "remote")),
            confidence = 0.72f,
        )
    }
}

private fun noResult(call: AgentToolCall, summary: String): AgentToolResult =
    AgentToolResult(
        call = call,
        summary = summary,
        facts = emptyList(),
        sources = listOf(AgentSource(summary.substringBefore("未命中"), "remote")),
        confidence = 0f,
    )

object ScmSearchTermExpander {
    private val noise = Regex(
        """(请问|帮我|查一下|查询|查|搜一下|搜|就叫|叫|名称是|名字是|物品是|东西是|你|scm|市场|订单|挂单)""",
        RegexOption.IGNORE_CASE,
    )

    fun expand(term: String, entityIndex: AgentEntityIndex): List<String> {
        val cleaned = clean(term)
        val compact = AgentAliasNormalizer.compact(cleaned)
        val candidates = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.length >= 2 }?.let { candidates += it }
        }
        add(cleaned)
        add(term)
        if (compact.length >= 2) {
            entityIndex.entries.forEach { entity ->
                val values = listOf(entity.displayName, entity.value) + entity.aliases
                if (values.any { fuzzyContains(it, compact) }) {
                    add(entity.displayName)
                    add(entity.value)
                    entity.aliases.forEach(::add)
                }
            }
        }
        return candidates.toList()
    }

    fun clean(term: String): String =
        term.replace(noise, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun fuzzyContains(value: String, compactTerm: String): Boolean {
        val compactValue = AgentAliasNormalizer.compact(value)
        return compactValue.length >= 2 &&
            (compactValue.contains(compactTerm) || compactTerm.contains(compactValue))
    }
}

private fun JSONObject.blueprintDisplayName(): String {
    val cn = optString("blueprintNameCn").trim()
    val en = optString("blueprintName").trim()
    return when {
        cn.isNotBlank() && en.isNotBlank() && cn != en -> "$cn / $en"
        cn.isNotBlank() -> cn
        else -> en
    }
}

private fun formatPrice(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

private fun formatPrice(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()
