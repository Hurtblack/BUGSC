package com.euedrc.bugsc.data

import org.junit.Assert.assertSame
import org.junit.Test

class AppServicesTest {
    @Test
    fun overridesAndResetsShipOnlineSource() {
        val fake = object : ShipOnlineDataSource by DisabledShipOnlineDataSource {}

        AppServices.overrideForTest(shipOnline = fake)
        assertSame(fake, AppServices.shipOnline)

        AppServices.resetForTest()
        assertSame(DisabledShipOnlineDataSource, AppServices.shipOnline)
    }
}
