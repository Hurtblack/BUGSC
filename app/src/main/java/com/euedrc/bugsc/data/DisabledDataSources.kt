package com.euedrc.bugsc.data

import com.euedrc.bugsc.market.MarketPage
import com.euedrc.bugsc.market.publish.ItemImageStatus
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishItemValue
import com.euedrc.bugsc.market.publish.PublishOrderPage
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import com.euedrc.bugsc.market.publish.UploadResult
import com.euedrc.bugsc.market.transaction.AddressNode
import com.euedrc.bugsc.market.transaction.TransactionCreateResult
import com.euedrc.bugsc.market.transaction.TransactionDraft
import com.euedrc.bugsc.market.transaction.TransactionPage
import com.euedrc.bugsc.market.transaction.TransactionRecord
import java.io.File
import java.math.BigDecimal

object DisabledShipOnlineDataSource : ShipOnlineDataSource {
    override suspend fun searchShips(query: String, limit: Int): List<OnlineShipSearchResult> = emptyList()
    override suspend fun getShipDetail(id: String): OnlineShipDetail? = null
    override suspend fun getComponentDetail(type: String, id: String): OnlineComponentDetail? = null
}

object DisabledAuthDataSource : AuthDataSource {
    override fun isLoggedIn(): Boolean = false
    override fun currentUserId(): Long = 0L
    override suspend fun login(
        email: String,
        password: String,
        captchaVerification: String,
        emailCode: String?,
    ): AppLoginResult = AppLoginResult.Failure("当前构建未启用 SCM 登录")
    override suspend fun logout() = Unit
    override suspend fun getUserInfo(): AppUserProfile? = null
}

object DisabledMarketOrderDataSource : MarketOrderDataSource {
    override suspend fun fetchPage(
        creatorType: Int,
        pageNo: Int,
        pageSize: Int,
        keyword: String,
    ): MarketPage = MarketPage(emptyList(), 0)
    override suspend fun fetchDetail(orderNumber: String) = null
}

object DisabledTransactionDataSource : TransactionDataSource {
    override suspend fun create(draft: TransactionDraft): TransactionCreateResult =
        throw IllegalStateException("当前构建未启用交易功能")
    override suspend fun checkOngoing(orderNumber: String): Boolean = false
    override suspend fun page(
        pageNo: Int,
        pageSize: Int,
        transactionStatus: Int?,
        orderNumber: String?,
    ): TransactionPage = TransactionPage(emptyList(), 0L)
    override suspend fun get(transactionNumber: String): TransactionRecord? = null
    override suspend fun updateStatus(transactionNumber: String, deliveryStatus: Int) = Unit
    override suspend fun approve(transactionNumber: String) = Unit
    override suspend fun addressList(): List<AddressNode> = emptyList()
}

object DisabledMarketPublishDataSource : MarketPublishDataSource {
    override val allowedMimeTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
    override suspend fun searchItems(keyword: String): List<ItemSearchResult> = emptyList()
    override suspend fun createItemList(title: String, items: List<PublishItemValue>): Long =
        throw IllegalStateException("当前构建未启用挂单发布功能")
    override suspend fun createOrder(
        creatorType: PublishCreatorType,
        locationId: Long,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        items: List<PublishItemValue>,
        manifestId: Long?,
    ): Long = throw IllegalStateException("当前构建未启用挂单发布功能")
    override suspend fun ownOrders(
        pageNo: Int,
        pageSize: Int,
        creatorType: PublishCreatorType?,
    ): PublishOrderPage = PublishOrderPage(emptyList(), 0L)
    override suspend fun updateOrder(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    ) = Unit
    override suspend fun setOrderStatus(orderNumber: String, status: PublishOrderStatus) = Unit
    override suspend fun deleteOrder(orderNumber: String) = Unit
    override suspend fun quantityAdd(orderNumber: String) = Unit
    override suspend fun imageStatus(itemId: Long): ItemImageStatus? = null
    override suspend fun uploadImage(itemId: Long, imageFile: File, mimeType: String): UploadResult =
        throw IllegalStateException("当前构建未启用图片上传功能")
}
