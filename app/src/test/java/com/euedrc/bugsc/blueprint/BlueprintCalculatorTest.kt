package com.euedrc.bugsc.blueprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlueprintCalculatorTest {

    private fun damageBlueprint(
        startQuality: Int = 0,
        endQuality: Int = 1000,
        atStart: Double = 0.0,
        atEnd: Double = 0.20,
        type: ModifierType = ModifierType.ADDITIVE,
        base: Double = 100.0,
    ) = Blueprint(
        categoryName = "FPSWeapons",
        baseStats = mapOf("Weapon_Damage" to base),
        slots = listOf(
            BlueprintSlot(
                name = "core",
                options = listOf(
                    MaterialOption(
                        resourceName = "Agricium",
                        modifiers = listOf(
                            QualityModifier(
                                "Weapon_Damage", startQuality, endQuality, atStart, atEnd, type
                            )
                        ),
                    )
                ),
            )
        ),
    )

    @Test
    fun `additive lerp at mid quality`() {
        val bp = damageBlueprint()
        val sel = CraftSelection(qualityBySlot = mapOf(0 to 500))
        assertEquals(110.0, BlueprintCalculator.finalStat(bp, sel, "Weapon_Damage"), 1e-9)
    }

    @Test
    fun `additive lerp at bounds`() {
        val bp = damageBlueprint()
        assertEquals(
            100.0,
            BlueprintCalculator.finalStat(bp, CraftSelection(qualityBySlot = mapOf(0 to 0)), "Weapon_Damage"),
            1e-9,
        )
        assertEquals(
            120.0,
            BlueprintCalculator.finalStat(bp, CraftSelection(qualityBySlot = mapOf(0 to 1000)), "Weapon_Damage"),
            1e-9,
        )
    }

    @Test
    fun `quality outside range yields zero modifier`() {
        val bp = damageBlueprint(startQuality = 200, endQuality = 800)
        // quality 100 < startQuality 200 -> modifier 0 -> final == base
        val sel = CraftSelection(qualityBySlot = mapOf(0 to 100))
        assertEquals(100.0, BlueprintCalculator.finalStat(bp, sel, "Weapon_Damage"), 1e-9)
    }

    @Test
    fun `default uses option minQuality when not specified`() {
        // minQuality 500, range 0..1000 atEnd 0.2 -> modifier 0.1 -> 110
        val bp = damageBlueprint()
        assertEquals(110.0, BlueprintCalculator.finalStat(bp, CraftSelection(), "Weapon_Damage"), 1e-9)
    }

    @Test
    fun `multiplicative modifier returns multiplier minus one`() {
        // multiplicative: atStart=1.0 atEnd=1.5, at quality 1000 -> returns 0.5 delta -> base*1.5
        val bp = damageBlueprint(atStart = 1.0, atEnd = 1.5, type = ModifierType.MULTIPLICATIVE)
        val sel = CraftSelection(qualityBySlot = mapOf(0 to 1000))
        assertEquals(150.0, BlueprintCalculator.finalStat(bp, sel, "Weapon_Damage"), 1e-9)
    }

    @Test
    fun `multiple slots accumulate on same property`() {
        val bp = Blueprint(
            categoryName = "FPSWeapons",
            baseStats = mapOf("Weapon_Damage" to 100.0),
            slots = listOf(
                BlueprintSlot("a", options = listOf(MaterialOption("X", modifiers = listOf(
                    QualityModifier("Weapon_Damage", 0, 1000, 0.0, 0.10, ModifierType.ADDITIVE))))),
                BlueprintSlot("b", options = listOf(MaterialOption("Y", modifiers = listOf(
                    QualityModifier("Weapon_Damage", 0, 1000, 0.0, 0.20, ModifierType.ADDITIVE))))),
            ),
        )
        // both at quality 1000 -> 0.10 + 0.20 = 0.30 -> 130
        val sel = CraftSelection(qualityBySlot = mapOf(0 to 1000, 1 to 1000))
        assertEquals(130.0, BlueprintCalculator.finalStat(bp, sel, "Weapon_Damage"), 1e-9)
    }

    @Test
    fun `dps combines damage and fire rate`() {
        val bp = Blueprint(
            categoryName = "FPSWeapons",
            baseStats = mapOf("raw_dps" to 100.0),
            slots = listOf(
                BlueprintSlot("a", options = listOf(MaterialOption("X", modifiers = listOf(
                    QualityModifier("Weapon_Damage", 0, 1000, 0.0, 0.10, ModifierType.ADDITIVE),
                    QualityModifier("Weapon_FireRate", 0, 1000, 0.0, 0.20, ModifierType.ADDITIVE),
                )))),
            ),
        )
        val sel = CraftSelection(qualityBySlot = mapOf(0 to 1000))
        // 100 * 1.10 * 1.20 = 132
        assertEquals(132.0, BlueprintCalculator.finalDps(bp, sel), 1e-9)
    }

    @Test
    fun `computeAll returns base when no modifiers`() {
        val bp = Blueprint("X", emptyList(), mapOf("A" to 50.0))
        val r = BlueprintCalculator.computeAll(bp, CraftSelection()).single()
        assertEquals(50.0, r.baseValue, 1e-9)
        assertEquals(50.0, r.finalValue, 1e-9)
        assertNull(r.propertyCn)
    }
}
