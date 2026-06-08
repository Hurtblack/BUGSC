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

    private fun slot(key: String, min: Int?, max: Int?, vararg types: String) =
        ShipSlot(key = key, minSize = min, maxSize = max, types = types.toList())

    @Test
    fun slotLabel_singleSlotNoSuffix() {
        val s = slot("hardpoint_cooler", 2, 2, "Cooler")
        assertEquals("冷却 · S2", ShipFitDisplay.slotLabel(s, listOf(s)))
    }

    @Test
    fun slotLabel_collapsesSizeAndDropsRawKey() {
        val s = slot("hardpoint_quantum_drive", 1, 1, "QuantumDrive")
        assertEquals("量子 · S1", ShipFitDisplay.slotLabel(s, listOf(s)))
    }

    @Test
    fun slotLabel_usesPositionWhenAllDistinct() {
        val left = slot("fallback_power_plant_left", 2, 2, "PowerPlant")
        val right = slot("fallback_power_plant_right", 2, 2, "PowerPlant")
        val all = listOf(left, right)
        assertEquals("电源 · S2 · 左", ShipFitDisplay.slotLabel(left, all))
        assertEquals("电源 · S2 · 右", ShipFitDisplay.slotLabel(right, all))
    }

    @Test
    fun slotLabel_numbersWhenPositionUnavailable() {
        val a = slot("fallback_shield_1", 2, 2, "Shield")
        val b = slot("fallback_shield_2", 2, 2, "Shield")
        val c = slot("fallback_shield_3", 2, 2, "Shield")
        val all = listOf(a, b, c)
        assertEquals("护盾 · S2 · ①", ShipFitDisplay.slotLabel(a, all))
        assertEquals("护盾 · S2 · ②", ShipFitDisplay.slotLabel(b, all))
        assertEquals("护盾 · S2 · ③", ShipFitDisplay.slotLabel(c, all))
    }

    @Test
    fun slotLabel_numbersWhenSomePositionMissing() {
        val cockpit = slot("hardpoint_cockpit_radar", 1, 1, "Radar")
        val plain = slot("hardpoint_radar", 1, 1, "Radar")
        val all = listOf(cockpit, plain)
        assertEquals("雷达 · S1 · ①", ShipFitDisplay.slotLabel(cockpit, all))
        assertEquals("雷达 · S1 · ②", ShipFitDisplay.slotLabel(plain, all))
    }

    @Test
    fun slotLabel_moduleChildIndented() {
        val m = slot("hardpoint_mining_turret/module_2", null, null, "MiningModule")
        assertEquals("　模组 2 · 采矿模组", ShipFitDisplay.slotLabel(m, listOf(m)))
    }
}
