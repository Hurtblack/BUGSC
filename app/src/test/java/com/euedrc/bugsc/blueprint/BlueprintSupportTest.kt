package com.euedrc.bugsc.blueprint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintSupportTest {

    // ---- SimpleBlueprintCalculator / BlueprintHint ----

    private val crossbowJson = JSONObject(
        """{"type":"blueprint","categoryName":"FPSWeapons",
            "modifierRanges":{"Weapon_Damage":{"max":0.2,"min":-0.2}},
            "availableProperties":["Weapon_Damage"]}"""
    )

    @Test
    fun `hint parses ranges and properties`() {
        val hint = BlueprintHint.fromJson("Novian Crossbow", crossbowJson)
        assertEquals("FPSWeapons", hint.categoryName)
        assertEquals(listOf("Weapon_Damage"), hint.availableProperties)
        assertEquals(0.2, hint.modifierRanges.getValue("Weapon_Damage").max, 1e-9)
        assertEquals(-0.2, hint.modifierRanges.getValue("Weapon_Damage").min, 1e-9)
    }

    @Test
    fun `range lerps quality across min-max`() {
        val r = ModifierRange(min = -0.2, max = 0.2)
        assertEquals(-0.2, r.modifierAt(0), 1e-9)
        assertEquals(0.0, r.modifierAt(500), 1e-9)
        assertEquals(0.2, r.modifierAt(1000), 1e-9)
        // clamps out-of-range quality
        assertEquals(0.2, r.modifierAt(5000), 1e-9)
        assertEquals(-0.2, r.modifierAt(-100), 1e-9)
    }

    @Test
    fun `simple calculator applies modifier to base`() {
        val hint = BlueprintHint.fromJson("Novian Crossbow", crossbowJson)
        val results = SimpleBlueprintCalculator.compute(
            hint, quality = 1000, baseByProperty = mapOf("Weapon_Damage" to 100.0)
        )
        val r = results.single()
        assertEquals(0.2, r.modifier, 1e-9)
        assertEquals(120.0, r.finalValue!!, 1e-9)
        assertEquals(20.0, r.modifierPercent, 1e-9)
    }

    @Test
    fun `simple calculator without base gives only modifier`() {
        val hint = BlueprintHint.fromJson("Novian Crossbow", crossbowJson)
        val r = SimpleBlueprintCalculator.compute(hint, quality = 0).single()
        assertEquals(-0.2, r.modifier, 1e-9)
        assertNull(r.finalValue)
    }

    // ---- UexDto / UexMapper / CodexTranslations ----

    private val itemsJson = JSONObject(
        """{"status":"ok","http_code":200,"data":[
            {"id":170,"id_category":3,"id_company":217,"name":"Venture Helmet White",
             "uuid":"abc","section":"Armor","category":"Helmets","company_name":"RSI",
             "is_harvestable":0,"is_commodity":0,"screenshot":""}
        ]}"""
    )
    private val attrsJson = JSONObject(
        """{"status":"ok","http_code":200,"data":[
            {"id":1,"id_item":170,"item_uuid":"abc","attribute_name":"Damage Reduction","value":"20","unit":"%"},
            {"id":2,"id_item":170,"item_uuid":"abc","attribute_name":"Armor Class","value":"Light","unit":""}
        ]}"""
    )

    @Test
    fun `mapper joins items with attributes and applies translation`() {
        val items = UexDto.parseItems(itemsJson)
        val attrs = UexDto.parseItemAttributes(attrsJson)
        val t = CodexTranslations.fromJson(
            JSONObject("""{"items_by_en":{"Venture Helmet White":"冒险者头盔 白色"},
                           "attributes":{"Damage Reduction":"伤害减免"}}""")
        )
        val item = UexMapper.toItems(items, attrs, t).single()
        assertEquals("冒险者头盔 白色", item.nameCn)
        assertEquals("冒险者头盔 白色", item.displayName)
        assertEquals(2, item.attributes.size)

        val dmg = item.attributes.first { it.nameEn == "Damage Reduction" }
        assertEquals("伤害减免", dmg.nameCn)
        assertEquals(20.0, dmg.numericValue!!, 1e-9)

        // 非数值属性不进入 numericAttributeMap
        assertEquals(mapOf("Damage Reduction" to 20.0), item.numericAttributeMap())
    }

    @Test
    fun `mapper falls back to english when no translation`() {
        val items = UexDto.parseItems(itemsJson)
        val item = UexMapper.toItems(items, emptyList(), CodexTranslations.EMPTY).single()
        assertNull(item.nameCn)
        assertEquals("Venture Helmet White", item.displayName)
        assertTrue(item.attributes.isEmpty())
    }

    @Test
    fun `dto treats non-ok status as empty`() {
        val bad = JSONObject("""{"status":"requires_id","http_code":400,"data":[]}""")
        assertTrue(UexDto.parseItems(bad).isEmpty())
    }
}
