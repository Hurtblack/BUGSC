package com.euedrc.bugsc.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataSourceContractsTest {
    @Test
    fun disabledShipSourceReturnsEmptyResults() = runBlocking {
        val source: ShipOnlineDataSource = DisabledShipOnlineDataSource

        assertEquals(emptyList<OnlineShipSearchResult>(), source.searchShips("北极星", 12))
        assertNull(source.getShipDetail("rsi-polaris"))
        assertNull(source.getComponentDetail("Missile", "MISL_S10_IR_BEHR_Torpedo"))
    }
}
