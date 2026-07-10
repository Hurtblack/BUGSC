package com.euedrc.bugsc.data

import android.content.Context

object DistributionServices {
    fun install(context: Context) {
        AppServices.install(
            auth = DisabledAuthDataSource,
            marketOrders = DisabledMarketOrderDataSource,
            transactions = DisabledTransactionDataSource,
            marketPublish = DisabledMarketPublishDataSource,
            shipOnline = DisabledShipOnlineDataSource,
        )
        PrivateDistributionBridge.installIfPresent(context)
    }
}
