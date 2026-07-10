package com.euedrc.bugsc.data

import com.euedrc.bugsc.market.MarketOrder
import com.euedrc.bugsc.market.MarketPage

interface MarketOrderDataSource {
    suspend fun fetchPage(
        creatorType: Int,
        pageNo: Int,
        pageSize: Int = 10,
        keyword: String = "",
    ): MarketPage
    suspend fun fetchDetail(orderNumber: String): MarketOrder?
}
