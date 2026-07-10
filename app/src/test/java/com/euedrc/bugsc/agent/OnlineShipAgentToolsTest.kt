package com.euedrc.bugsc.agent

import com.euedrc.bugsc.data.DisabledShipOnlineDataSource
import com.euedrc.bugsc.data.OnlineComponentDetail
import com.euedrc.bugsc.data.OnlineShipDetail
import com.euedrc.bugsc.data.OnlineShipSearchResult
import com.euedrc.bugsc.data.ShipOnlineDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineShipAgentToolsTest {
    @Test
    fun registersSearchShipDetailAndComponentDetailTools() {
        val tools = OnlineShipAgentTools.create(DisabledShipOnlineDataSource)

        assertEquals(
            listOf("search_online_ship", "get_online_ship_detail", "get_online_component_detail"),
            tools.map { it.name },
        )
    }

    @Test
    fun searchReturnsDisabledMessageWhenNoOnlineSourceData() = runBlocking {
        val tool = OnlineShipSearchTool(DisabledShipOnlineDataSource)
        val result = tool.run(AgentToolCall("search_online_ship", mapOf("query" to "北极星")))

        assertTrue(result.summary.contains("未启用") || result.summary.contains("暂无线上结果"))
    }

    @Test
    fun searchFormatsOnlineShipResult() = runBlocking {
        val source = object : ShipOnlineDataSource {
            override suspend fun searchShips(query: String, limit: Int) = listOf(
                OnlineShipSearchResult("ships", "rsi-polaris", "北极星", "北极星", "Polaris"),
            )
            override suspend fun getShipDetail(id: String) =
                OnlineShipDetail(id, "北极星", "北极星", "Polaris", """{"ok":true}""")
            override suspend fun getComponentDetail(type: String, id: String) = null
        }
        val tool = OnlineShipSearchTool(source)
        val result = tool.run(AgentToolCall("search_online_ship", mapOf("query" to "北极星")))

        assertTrue(result.summary.contains("rsi-polaris"))
        assertTrue(result.summary.contains("Polaris"))
    }

    @Test
    fun shipDetailFormatsHardpointsAndRelatedItemIds() = runBlocking {
        val source = object : ShipOnlineDataSource {
            override suspend fun searchShips(query: String, limit: Int) = emptyList<OnlineShipSearchResult>()
            override suspend fun getShipDetail(id: String) = OnlineShipDetail(
                id = id,
                title = "北极星",
                nameCn = "北极星",
                nameEn = "Polaris",
                rawJson = """{
                    "manufacturer_cn":"RSI",
                    "role":"军用",
                    "hardpoints":{"torpedoes":{"label":"鱼雷","items":[{
                        "name_cn":"北极星鱼雷架","size":10,"controlled_by":"Pilot",
                        "children":[{"name_cn":"VT-T10 真理鱼雷","count":7,
                        "related":{"resource":"missiles","id":"misl_s10_ir_behr_torpedo"}}]
                    }]}}
                }""".trimIndent(),
            )
            override suspend fun getComponentDetail(type: String, id: String) = null
        }

        val result = OnlineShipDetailTool(source).run(
            AgentToolCall("get_online_ship_detail", mapOf("id" to "rsi-polaris")),
        )

        assertTrue(result.summary.contains("北极星鱼雷架"))
        assertTrue(result.summary.contains("VT-T10 真理鱼雷"))
        assertTrue(result.summary.contains("missiles/misl_s10_ir_behr_torpedo"))
        assertTrue(result.facts.any { it.label == "可继续查询的关联物品" })
    }

    @Test
    fun componentDetailFormatsUsefulStats() = runBlocking {
        val source = object : ShipOnlineDataSource {
            override suspend fun searchShips(query: String, limit: Int) = emptyList<OnlineShipSearchResult>()
            override suspend fun getShipDetail(id: String) = null
            override suspend fun getComponentDetail(type: String, id: String) = OnlineComponentDetail(
                type = type,
                id = id,
                rawJson = """{"name_cn":"VT-T10 真理鱼雷","size":10,"stats":{"Range":40500,"Speed":270}}""",
            )
        }

        val result = OnlineComponentDetailTool(source).run(
            AgentToolCall(
                "get_online_component_detail",
                mapOf("type" to "missiles", "id" to "misl_s10_ir_behr_torpedo"),
            ),
        )

        assertTrue(result.summary.contains("VT-T10 真理鱼雷"))
        assertTrue(result.summary.contains("40500"))
        assertTrue(result.summary.contains("270"))
    }
}
