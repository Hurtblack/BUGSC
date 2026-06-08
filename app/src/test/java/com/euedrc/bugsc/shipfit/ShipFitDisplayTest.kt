package com.euedrc.bugsc.shipfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipFitDisplayTest {

    @Test
    fun categoryLabel_mapsKnownTypes() {
        assertEquals("电源", ShipFitDisplay.categoryLabel("power_plant"))
        assertEquals("采矿头", ShipFitDisplay.categoryLabel("mining_laser"))
        assertEquals("采矿模组", ShipFitDisplay.categoryLabel("mining_module"))
        assertEquals("unknown_x", ShipFitDisplay.categoryLabel("unknown_x"))
    }

    @Test
    fun mapErkulType_mapsAndFallsBackToNull() {
        assertEquals("power_plant", ShipFitDisplay.mapErkulTypeToUexType("PowerPlant"))
        assertEquals("mining_laser", ShipFitDisplay.mapErkulTypeToUexType("UtilityTurret"))
        assertNull(ShipFitDisplay.mapErkulTypeToUexType("Nope"))
    }

    @Test
    fun isSelectableComponent_excludesVehicleExclusive() {
        assertTrue(ShipFitDisplay.isSelectableComponent(null))
        assertTrue(ShipFitDisplay.isSelectableComponent(""))
        assertTrue(ShipFitDisplay.isSelectableComponent("   "))
        assertFalse(ShipFitDisplay.isSelectableComponent("Reclaimer"))
    }

    @Test
    fun formatSize_collapsesAndFormats() {
        assertEquals("S2", ShipFitDisplay.formatSize(2, 2))
        assertEquals("S1-S3", ShipFitDisplay.formatSize(1, 3))
        assertEquals("S2", ShipFitDisplay.formatSize(2, null))
        assertEquals("≤S2", ShipFitDisplay.formatSize(null, 2))
        assertNull(ShipFitDisplay.formatSize(null, null))
    }

    @Test
    fun isSizeCompatible_sizedComponentRespectsBounds() {
        assertTrue(ShipFitDisplay.isSizeCompatible(2, 2, 2))
        assertFalse(ShipFitDisplay.isSizeCompatible(2, 2, 3))
        assertTrue(ShipFitDisplay.isSizeCompatible(1, 3, 3))
        assertFalse(ShipFitDisplay.isSizeCompatible(1, 3, 4))
    }

    @Test
    fun isSizeCompatible_unknownComponentSizeBlockedByConstraint() {
        assertFalse(ShipFitDisplay.isSizeCompatible(3, 3, null))
        assertFalse(ShipFitDisplay.isSizeCompatible(null, 3, null))
        assertTrue(ShipFitDisplay.isSizeCompatible(null, null, null))
    }

    @Test
    fun isSizeCompatible_unknownSlotClampsToS1() {
        assertTrue(ShipFitDisplay.isSizeCompatible(null, null, 1))
        assertFalse(ShipFitDisplay.isSizeCompatible(null, null, 4))
    }

    @Test
    fun positionLabel_translatesDirections() {
        assertEquals("左", ShipFitDisplay.positionLabel("hardpoint_shield_generator_left"))
        assertEquals("右", ShipFitDisplay.positionLabel("hardpoint_power_plant_right"))
        assertEquals("前左", ShipFitDisplay.positionLabel("hardpoint_weapon_frontleft"))
        assertEquals("后右", ShipFitDisplay.positionLabel("hardpoint_weapon_rearright"))
    }

    @Test
    fun positionLabel_translatesDescriptors() {
        assertEquals("座舱", ShipFitDisplay.positionLabel("hardpoint_cockpit_radar"))
        assertEquals("主", ShipFitDisplay.positionLabel("hardpoint_main_radar"))
    }

    @Test
    fun positionLabel_nullWhenNoPositionToken() {
        assertNull(ShipFitDisplay.positionLabel("hardpoint_cooler"))
        assertNull(ShipFitDisplay.positionLabel("hardpoint_radar"))
        assertNull(ShipFitDisplay.positionLabel("fallback_shield_1"))
        assertNull(ShipFitDisplay.positionLabel("wiki_PowerPlant_0"))
    }
}
