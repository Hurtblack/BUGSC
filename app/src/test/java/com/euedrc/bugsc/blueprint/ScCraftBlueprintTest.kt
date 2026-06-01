package com.euedrc.bugsc.blueprint

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScCraftBlueprintTest {

    private fun effect(
        stat: String = "Integrity",
        qMin: Int = 0, qMax: Int = 1000,
        atMin: Double = 0.9, atMax: Double = 1.1,
    ) = ScCraftEffect(stat, "statname_$stat", qMin, qMax, atMin, atMax)

    private fun slot(
        name: String = "FRAME",
        material: String = "Iron",
        effects: List<ScCraftEffect> = listOf(effect()),
    ) = ScCraftSlot(name, "crafting_ui_slotname_frame", material, "items_commodities_iron",
        0.64, 1, effects)

    // ---- ScCraftEffect ----

    @Test fun `effect multiplierAt midpoint`() {
        val e = effect(atMin = 0.9, atMax = 1.1)
        assertEquals(1.0, e.multiplierAt(500), 1e-9)
    }

    @Test fun `effect multiplierAt min`() {
        val e = effect(atMin = 0.9, atMax = 1.1)
        assertEquals(0.9, e.multiplierAt(0), 1e-9)
    }

    @Test fun `effect multiplierAt max`() {
        val e = effect(atMin = 0.9, atMax = 1.1)
        assertEquals(1.1, e.multiplierAt(1000), 1e-9)
    }

    @Test fun `effect deltaAt midpoint is zero`() {
        val e = effect(atMin = 0.9, atMax = 1.1)
        assertEquals(0.0, e.deltaAt(500), 1e-9)
    }

    @Test fun `effect clamps quality below min`() {
        val e = effect(qMin = 200, qMax = 800, atMin = 0.8, atMax = 1.2)
        assertEquals(0.8, e.multiplierAt(0), 1e-9)   // clamps to qMin
    }

    @Test fun `effect clamps quality above max`() {
        val e = effect(qMin = 200, qMax = 800, atMin = 0.8, atMax = 1.2)
        assertEquals(1.2, e.multiplierAt(1000), 1e-9) // clamps to qMax
    }

    // ---- ScCraftBlueprint.aggregateDeltas ----

    @Test fun `aggregateDeltas single slot at max quality`() {
        val bp = ScCraftBlueprint(
            nameEn = "Test Gun",
            blueprintId = "bp_test",
            category = "Weapons",
            craftTimeSeconds = 600,
            tiers = 1,
            slots = listOf(slot("FRAME", effects = listOf(effect("Integrity", atMin = 0.9, atMax = 1.1)))),
            missions = emptyList(),
        )
        val deltas = bp.aggregateDeltas(mapOf("FRAME" to 1000))
        assertEquals(0.1, deltas["Integrity"]!!, 1e-9)
    }

    @Test fun `aggregateDeltas two slots same stat sum deltas`() {
        val bp = ScCraftBlueprint(
            nameEn = "Test Gun",
            blueprintId = "bp_test",
            category = "Weapons",
            craftTimeSeconds = 600,
            tiers = 1,
            slots = listOf(
                slot("FRAME", effects = listOf(effect("Damage", atMin = 0.95, atMax = 1.05))),
                slot("BARREL", effects = listOf(effect("Damage", atMin = 0.95, atMax = 1.05))),
            ),
            missions = emptyList(),
        )
        val deltas = bp.aggregateDeltas(mapOf("FRAME" to 1000, "BARREL" to 1000))
        assertEquals(0.1, deltas["Damage"]!!, 1e-9) // 0.05 + 0.05
    }

    @Test fun `aggregateDeltas missing slot defaults to quality 0`() {
        val bp = ScCraftBlueprint(
            nameEn = "Test Gun",
            blueprintId = "bp_test",
            category = "Weapons",
            craftTimeSeconds = 600,
            tiers = 1,
            slots = listOf(slot("FRAME", effects = listOf(effect("Integrity")))),
            missions = emptyList(),
        )
        val deltas = bp.aggregateDeltas(emptyMap()) // no quality specified → 0
        assertEquals(-0.1, deltas["Integrity"]!!, 1e-9) // multiplierAt(0)=0.9 → delta -0.1
    }

    // ---- toBlueprint + BlueprintCalculator ----

    @Test fun `toBlueprint and calculator gives correct final stat`() {
        val bp = ScCraftBlueprint(
            nameEn = "Test Gun",
            blueprintId = "bp_test",
            category = "Weapons",
            craftTimeSeconds = 600,
            tiers = 1,
            slots = listOf(slot("FRAME", effects = listOf(effect("Integrity", atMin = 0.9, atMax = 1.1)))),
            missions = emptyList(),
        ).toBlueprint(baseStats = mapOf("Integrity" to 100.0))

        val selection = CraftSelection(qualityBySlot = mapOf(0 to 1000))
        val result = BlueprintCalculator.finalStat(bp, selection, "Integrity")
        assertEquals(110.0, result, 1e-6) // 100 * 1.1
    }

    // ---- fromJson round-trip ----

    @Test fun `fromJson parses correctly`() {
        val json = JSONObject("""{
            "blueprintId":"bp_test_gun",
            "category":"Weapons",
            "craftTimeSeconds":600,
            "tiers":1,
            "slots":[{
                "slot":"FRAME","slotLocKey":"crafting_ui_slotname_frame",
                "material":"Iron","materialLocKey":"items_commodities_iron",
                "quantityScu":0.64,"minQuality":1,
                "qualityEffects":[{
                    "stat":"Integrity","statLocKey":"statname_gpp_health_maxhealth",
                    "qualityMin":0,"qualityMax":1000,
                    "modifierAtMin":0.9,"modifierAtMax":1.1
                }]
            }],
            "missions":[{"missionId":12345,"name":"Hunt mission","dropChance":1.0}]
        }""")
        val bp = ScCraftBlueprint.fromJson("Test Gun", json)
        assertEquals("Test Gun", bp.nameEn)
        assertEquals("bp_test_gun", bp.blueprintId)
        assertEquals(1, bp.slots.size)
        assertEquals("FRAME", bp.slots[0].slot)
        assertEquals("Iron", bp.slots[0].material)
        assertEquals(1, bp.slots[0].qualityEffects.size)
        assertEquals("Integrity", bp.slots[0].qualityEffects[0].stat)
        assertEquals(0.9, bp.slots[0].qualityEffects[0].modifierAtMin, 1e-9)
        assertEquals(1, bp.missions.size)
        assertEquals(12345L, bp.missions[0].missionId)
    }
}
