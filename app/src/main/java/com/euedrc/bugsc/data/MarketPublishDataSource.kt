package com.euedrc.bugsc.data

import com.euedrc.bugsc.market.publish.ItemImageStatus
import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.OwnMarketOrder
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishItemValue
import com.euedrc.bugsc.market.publish.PublishOrderPage
import com.euedrc.bugsc.market.publish.PublishOrderStatus
import com.euedrc.bugsc.market.publish.UploadResult
import java.io.File
import java.math.BigDecimal

interface MarketPublishDataSource {
    val allowedMimeTypes: Set<String>
    suspend fun searchItems(keyword: String): List<ItemSearchResult>
    suspend fun createItemList(title: String, items: List<PublishItemValue>): Long
    suspend fun createOrder(
        creatorType: PublishCreatorType,
        locationId: Long,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        items: List<PublishItemValue>,
        manifestId: Long? = null,
    ): Long
    suspend fun ownOrders(
        pageNo: Int,
        pageSize: Int = 20,
        creatorType: PublishCreatorType? = null,
    ): PublishOrderPage
    suspend fun updateOrder(
        orderNumber: String,
        unitPrice: BigDecimal,
        remainingQuantity: Int,
        status: PublishOrderStatus,
        expireTime: PublishExpireTime,
        quality: Int?,
    )
    suspend fun setOrderStatus(orderNumber: String, status: PublishOrderStatus)
    suspend fun deleteOrder(orderNumber: String)
    suspend fun quantityAdd(orderNumber: String)
    suspend fun imageStatus(itemId: Long): ItemImageStatus?
    suspend fun uploadImage(itemId: Long, imageFile: File, mimeType: String): UploadResult
}
