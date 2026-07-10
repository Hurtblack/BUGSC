package com.euedrc.bugsc.data

object AppServices {
    var auth: AuthDataSource = DisabledAuthDataSource
        private set
    var marketOrders: MarketOrderDataSource = DisabledMarketOrderDataSource
        private set
    var transactions: TransactionDataSource = DisabledTransactionDataSource
        private set
    var marketPublish: MarketPublishDataSource = DisabledMarketPublishDataSource
        private set
    var shipOnline: ShipOnlineDataSource = DisabledShipOnlineDataSource
        private set

    fun install(
        auth: AuthDataSource = this.auth,
        marketOrders: MarketOrderDataSource = this.marketOrders,
        transactions: TransactionDataSource = this.transactions,
        marketPublish: MarketPublishDataSource = this.marketPublish,
        shipOnline: ShipOnlineDataSource = this.shipOnline,
    ) {
        this.auth = auth
        this.marketOrders = marketOrders
        this.transactions = transactions
        this.marketPublish = marketPublish
        this.shipOnline = shipOnline
    }

    internal fun overrideForTest(
        auth: AuthDataSource = this.auth,
        marketOrders: MarketOrderDataSource = this.marketOrders,
        transactions: TransactionDataSource = this.transactions,
        marketPublish: MarketPublishDataSource = this.marketPublish,
        shipOnline: ShipOnlineDataSource = this.shipOnline,
    ) = install(auth, marketOrders, transactions, marketPublish, shipOnline)

    internal fun resetForTest() = install(
        auth = DisabledAuthDataSource,
        marketOrders = DisabledMarketOrderDataSource,
        transactions = DisabledTransactionDataSource,
        marketPublish = DisabledMarketPublishDataSource,
        shipOnline = DisabledShipOnlineDataSource,
    )
}
