package com.euedrc.bugsc.market.transaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TransactionActionPolicyTest {
    @Test
    fun pendingApprovalBuyOrderCanBeCancelledByBuyer() {
        val actions = TransactionActionPolicy.visibleActions(
            record(creatorType = 0, deliveryStatus = 0),
            currentUserId = 100,
        )

        assertEquals(listOf(TransactionAction.CANCEL), actions)
    }

    @Test
    fun pendingApprovalBuyOrderCannotBeCancelledBySeller() {
        val actions = TransactionActionPolicy.visibleActions(
            record(creatorType = 0, deliveryStatus = 0),
            currentUserId = 200,
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun pendingDeliveryTransactionCanBeDeliveredBySellerForBuyOrder() {
        val actions = TransactionActionPolicy.visibleActions(
            record(creatorType = 0, deliveryStatus = 1),
            currentUserId = 200,
        )

        assertEquals(listOf(TransactionAction.DELIVER), actions)
    }

    @Test
    fun pendingDeliveryTransactionCanBeDeliveredBySellerForSellOrder() {
        val actions = TransactionActionPolicy.visibleActions(
            record(creatorType = 1, deliveryStatus = 1),
            currentUserId = 100,
        )

        assertEquals(listOf(TransactionAction.DELIVER), actions)
    }

    @Test
    fun completedTransactionHasNoActions() {
        assertTrue(
            TransactionActionPolicy.visibleActions(
                record(transactionStatus = 2, deliveryStatus = 1),
                currentUserId = 100,
            ).isEmpty()
        )
    }

    private fun record(
        creatorType: Int = 1,
        transactionStatus: Int = 1,
        deliveryStatus: Int = 1,
    ) = TransactionRecord(
        transactionNumber = "T1",
        orderOwnerId = 100,
        orderOwnerName = "owner",
        orderOwnerAvatar = "",
        orderOwnerMobile = "",
        tradingerId = 200,
        tradingerName = "trader",
        tradingerAvatar = "",
        tradingerMobile = "",
        creatorStatus = 1,
        creatorType = creatorType,
        itemsName = "item",
        thumbnailUrl = "",
        number = 1,
        remainingQuantity = 1,
        amount = BigDecimal.ONE,
        transactionStatus = transactionStatus,
        deliveryStatus = deliveryStatus,
        locationName = "",
        transactionLocationName = "",
        deliveryMethod = 0,
        shippingFee = BigDecimal.ZERO,
        createTime = "",
        completionTime = "",
        cancellationTime = "",
        receiptDeadline = "",
    )
}
