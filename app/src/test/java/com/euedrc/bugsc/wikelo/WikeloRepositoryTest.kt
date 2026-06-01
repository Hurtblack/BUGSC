package com.euedrc.bugsc.wikelo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 不依赖 Android 框架的纯 JVM 测试 —— 直接读取 assets/wikelo 下的 JSON, 校验:
 *   1. 文件能解析, 关键字段齐全
 *   2. 所有材料 qty 均非 null (我们已补全)
 *   3. 不可用任务数符合预期 (石英3 + DCP3 + 升天6 = 12)
 *   4. 关键任务(Polaris/Idris-P/游隼维克洛改版) 字段正确
 *   5. 材料反查可命中典型材料
 */
class WikeloRepositoryTest {

    private val tradesJson by lazy { loadJson("banu_trades.json") }
    private val materialsJson by lazy { loadJson("banu_materials.json") }

    @Test
    fun `trades json has prerequisites and trades arrays`() {
        val pre = tradesJson.optJSONArray("prerequisites")!!
        val tr = tradesJson.optJSONArray("trades")!!
        assertTrue("应至少 1 个前置任务", pre.length() >= 1)
        // 前置任务"维克洛来到星系"需要 苔原寇骈犬角x3 + 纯净水x1
        val firstPre = pre.getJSONObject(0)
        assertEquals("维克洛来到星系", firstPre.optString("name_cn"))
        assertEquals(2, firstPre.getJSONArray("materials").length())
        assertTrue("应有数十条交易", tr.length() > 50)
    }

    @Test
    fun `materials json has materials array`() {
        val ma = materialsJson.optJSONArray("materials")!!
        assertTrue(ma.length() > 50)
        // 每条至少有 name_en / name_cn / acquisition
        for (i in 0 until ma.length()) {
            val o = ma.getJSONObject(i)
            assertTrue("第 $i 条缺 name_cn", o.optString("name_cn").isNotBlank())
            assertNotNull("第 $i 条缺 acquisition", o.optJSONArray("acquisition"))
        }
    }

    @Test
    fun `all material quantities are filled (no qty null)`() {
        val secs = listOf("prerequisites", "trades")
        val nullOnes = mutableListOf<String>()
        for (sec in secs) {
            val arr = tradesJson.optJSONArray(sec) ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val name = t.optString("name_cn")
                val mats = t.optJSONArray("materials") ?: continue
                for (mi in 0 until mats.length()) {
                    val m = mats.getJSONObject(mi)
                    if (m.isNull("qty")) nullOnes += "$name / ${m.optString("name_cn")}"
                }
            }
        }
        assertEquals("存在未填数量的材料: $nullOnes", 0, nullOnes.size)
    }

    @Test
    fun `unavailable count matches expected 12 (石英3 + DCP3 + 升天6)`() {
        val byCat = HashMap<String, Int>()
        val arr = tradesJson.getJSONArray("trades")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (!t.optBoolean("available", true)) {
                val c = t.optString("category")
                byCat[c] = (byCat[c] ?: 0) + 1
            }
        }
        assertEquals(3, byCat["石英"] ?: 0)
        assertEquals(3, byCat["DCP"] ?: 0)
        assertEquals(6, byCat["升天"] ?: 0)
        assertEquals(12, byCat.values.sum())
    }

    @Test
    fun `Polaris special exists with reward 1000 and 12 materials`() {
        val polaris = findTrade("北极星 维克洛特别版")!!
        assertEquals("special", polaris.optString("branch"))
        val rep = polaris.getJSONObject("reputation")
        assertEquals(1000, rep.optInt("reward"))
        assertTrue(rep.isNull("required_tier"))
        assertEquals(12, polaris.getJSONArray("materials").length())
    }

    @Test
    fun `Idris-P requires tier 3 with reward null`() {
        val idris = findTrade("伊德里斯P 维克洛战争特别版")!!
        val rep = idris.getJSONObject("reputation")
        assertTrue(rep.isNull("reward"))
        assertEquals(3, rep.optInt("required_tier"))
    }

    @Test
    fun `Peregrine Wikelo Mod L-22 has reward 30 (per user-confirmed value)`() {
        val arr = tradesJson.getJSONArray("trades")
        var found: JSONObject? = null
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optString("name_cn") == "游隼维克洛改版" &&
                t.optString("reward_item").startsWith("L-22")) {
                found = t
                break
            }
        }
        assertNotNull("游隼维克洛改版 (L-22头狼) 未找到", found)
        assertEquals(30, found!!.getJSONObject("reputation").optInt("reward"))
    }

    @Test
    fun `material lookup index covers common materials`() {
        val idx = buildAcquisitionIndex()
        // 几个关键材料应有获取途径
        for (name in listOf("人情", "科力晶", "科力晶(纯)", "王牌 截击者 头盔", "联科发安全驱动器")) {
            val acq = idx[name]
            assertNotNull("缺材料: $name", acq)
            assertTrue("$name 的 acquisition 应非空", acq!!.isNotEmpty())
        }
    }

    @Test
    fun `升天 group is all marked unavailable`() {
        val arr = tradesJson.getJSONArray("trades")
        var ssTotal = 0
        var ssUnavail = 0
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optString("category") == "升天") {
                ssTotal++
                if (!t.optBoolean("available", true)) ssUnavail++
            }
        }
        assertEquals(6, ssTotal)
        assertEquals(6, ssUnavail)
    }

    // ──────────── helpers ────────────

    private fun findTrade(nameCn: String): JSONObject? {
        for (sec in listOf("prerequisites", "trades")) {
            val arr = tradesJson.optJSONArray(sec) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name_cn") == nameCn) return o
            }
        }
        return null
    }

    private fun buildAcquisitionIndex(): Map<String, List<String>> {
        val arr = materialsJson.getJSONArray("materials")
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bag = out.getOrPut(o.optString("name_cn")) { LinkedHashSet() }
            val acq = o.optJSONArray("acquisition") ?: JSONArray()
            for (j in 0 until acq.length()) bag += acq.getString(j)
        }
        return out.mapValues { it.value.toList() }
    }

    private fun loadJson(name: String): JSONObject {
        // assets 路径相对工程根的位置
        val path = File("src/main/assets/wikelo/$name")
        require(path.exists()) { "找不到 $path (cwd=${File(".").absolutePath})" }
        return JSONObject(path.readText(Charsets.UTF_8))
    }
}
