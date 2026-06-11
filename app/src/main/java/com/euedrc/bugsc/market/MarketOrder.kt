package com.euedrc.bugsc.market

data class MarketOrderItem(
    val itemId: String,
    val itemName: String,
    val thumbnailUrl: String,
    val thumbnailUrlHd: String,
    val quantity: Int,
    val unitPrice: Double,
)

data class MarketOrder(
    val orderNumber: String,
    val creatorType: Int,
    val remainingQuantity: Int,
    val unitPrice: Double,
    val status: Int,
    val remark: String,
    val expireTime: Long,
    val createTime: Long,
    val nickname: String,
    val avatar: String,
    val point: Int,
    /** 1=在线 2=游戏中 3=隐身 0=离线（flowcld creatorStatus） */
    val creatorStatus: Int,
    val itemName: String,
    val locationName: String,
    val locationUrl: String,
    val tradeTime: String,
    val tradeStartTime: String,
    val tradeEndTime: String,
    val itemDetails: List<MarketOrderItem>,
) {
    val isSell: Boolean get() = creatorType == 1
    val thumbnailUrl: String get() = itemDetails.firstOrNull()?.thumbnailUrl.orEmpty()
    val thumbnailUrlHd: String get() = itemDetails.firstOrNull()?.thumbnailUrlHd.orEmpty()
}

data class MarketPage(
    val list: List<MarketOrder>,
    val total: Int,
)
