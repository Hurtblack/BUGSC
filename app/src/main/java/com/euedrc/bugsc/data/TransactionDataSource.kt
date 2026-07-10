package com.euedrc.bugsc.data

import com.euedrc.bugsc.market.transaction.AddressNode
import com.euedrc.bugsc.market.transaction.TransactionCreateResult
import com.euedrc.bugsc.market.transaction.TransactionDraft
import com.euedrc.bugsc.market.transaction.TransactionPage
import com.euedrc.bugsc.market.transaction.TransactionRecord

interface TransactionDataSource {
    suspend fun create(draft: TransactionDraft): TransactionCreateResult
    suspend fun checkOngoing(orderNumber: String): Boolean
    suspend fun page(
        pageNo: Int,
        pageSize: Int = 20,
        transactionStatus: Int? = null,
        orderNumber: String? = null,
    ): TransactionPage
    suspend fun get(transactionNumber: String): TransactionRecord?
    suspend fun updateStatus(transactionNumber: String, deliveryStatus: Int)
    suspend fun approve(transactionNumber: String)
    suspend fun addressList(): List<AddressNode>
}
