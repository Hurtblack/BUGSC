package com.euedrc.bugsc.data

data class OnlineShipSearchResult(
    val resource: String,
    val id: String,
    val title: String,
    val nameCn: String?,
    val nameEn: String?,
)

data class OnlineShipDetail(
    val id: String,
    val title: String,
    val nameCn: String?,
    val nameEn: String?,
    val rawJson: String,
)

data class OnlineComponentDetail(
    val type: String,
    val id: String,
    val rawJson: String,
)

interface ShipOnlineDataSource {
    suspend fun searchShips(query: String, limit: Int): List<OnlineShipSearchResult>
    suspend fun getShipDetail(id: String): OnlineShipDetail?
    suspend fun getComponentDetail(type: String, id: String): OnlineComponentDetail?
}
