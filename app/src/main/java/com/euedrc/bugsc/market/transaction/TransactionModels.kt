package com.euedrc.bugsc.market.transaction

import java.math.BigDecimal

data class AddressNode(
    val id: Long,
    val parentId: Long,
    val name: String,
)

data class AddressBranch(
    val node: AddressNode,
    val children: List<AddressBranch>,
)

data class AddressChoice(
    val label: String,
    val branch: AddressBranch?,
) {
    val locationIdIfLeaf: Long?
        get() = branch?.node?.id?.takeIf { branch.children.isEmpty() }

    companion object {
        fun withPrompt(prompt: String, branches: List<AddressBranch>): List<AddressChoice> =
            listOf(AddressChoice(prompt, null)) + branches.map { AddressChoice(it.node.name, it) }
    }
}

data class AddressTree(val roots: List<AddressBranch>) {
    fun isLeaf(id: Long): Boolean = find(roots, id)?.children?.isEmpty() == true

    private fun find(branches: List<AddressBranch>, id: Long): AddressBranch? {
        branches.forEach { branch ->
            if (branch.node.id == id) return branch
            find(branch.children, id)?.let { return it }
        }
        return null
    }

    companion object {
        fun build(nodes: List<AddressNode>): AddressTree {
            val childrenByParent = nodes.groupBy(AddressNode::parentId)
            fun branch(node: AddressNode): AddressBranch =
                AddressBranch(node, childrenByParent[node.id].orEmpty().map(::branch))

            val ids = nodes.mapTo(HashSet(), AddressNode::id)
            val roots = nodes.filter { it.parentId == 0L || it.parentId !in ids }.map(::branch)
            return AddressTree(roots)
        }
    }
}

enum class TransactionFilter(val apiStatus: Int?) {
    ALL(null),
    ONGOING(1),
    COMPLETED(2),
    CANCELLED(3),
}

data class TransactionDraft(
    val orderNumber: String,
    val number: Int,
    val locationId: Long,
    val deliveryMethod: Int = 0,
    val shippingFee: BigDecimal,
)

data class TransactionCreateResult(val identifier: String)

data class TransactionRecord(
    val transactionNumber: String,
    val orderOwnerId: Long,
    val orderOwnerName: String,
    val orderOwnerAvatar: String,
    val orderOwnerMobile: String,
    val tradingerId: Long,
    val tradingerName: String,
    val tradingerAvatar: String,
    val tradingerMobile: String,
    val creatorStatus: Int,
    val creatorType: Int,
    val itemsName: String,
    val thumbnailUrl: String,
    val number: Int,
    val remainingQuantity: Int,
    val amount: BigDecimal,
    val transactionStatus: Int,
    val deliveryStatus: Int,
    val locationName: String,
    val transactionLocationName: String,
    val deliveryMethod: Int,
    val shippingFee: BigDecimal,
    val createTime: String,
    val completionTime: String,
    val cancellationTime: String,
    val receiptDeadline: String,
) {
    val deliveryMethodLabel: String get() = if (deliveryMethod == 0) "面交" else "未知方式"
    val transactionStatusLabel: String
        get() = when (transactionStatus) {
            1 -> "进行中"
            2 -> "已完成"
            3 -> "已取消"
            else -> "未知"
        }
}

enum class TransactionAction {
    DELIVER,
    CANCEL,
}

object TransactionActionPolicy {
    fun visibleActions(record: TransactionRecord, currentUserId: Long): List<TransactionAction> {
        if (currentUserId <= 0 || record.transactionStatus != 1) return emptyList()
        val sellerId = if (record.creatorType == 1) record.orderOwnerId else record.tradingerId
        return buildList {
            if (record.deliveryStatus == 0 && currentUserId == record.tradingerId) {
                add(TransactionAction.CANCEL)
            }
            if (record.deliveryStatus == 1 && currentUserId == sellerId) {
                add(TransactionAction.DELIVER)
            }
        }
    }
}

data class TransactionPage(
    val items: List<TransactionRecord>,
    val total: Long,
)

class TransactionContractException(message: String) : IllegalStateException(message)
