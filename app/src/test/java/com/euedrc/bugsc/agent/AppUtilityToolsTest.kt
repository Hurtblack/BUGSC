package com.euedrc.bugsc.agent

import com.euedrc.bugsc.InventoryItem
import com.euedrc.bugsc.ServiceStatusLevel
import com.euedrc.bugsc.ToolHeaderStatus
import com.euedrc.bugsc.wb.WbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUtilityToolsTest {

    private class RecordingDailyWbProvider : SyncableDailyWbProvider {
        val calls = mutableListOf<String>()

        override fun sync(): AppToolSyncResult {
            calls += "sync"
            return AppToolSyncResult(success = true, message = "已同步每日 WB")
        }

        override fun generatedAt(): String {
            calls += "generatedAt"
            return "2026-06-09T04:55:43Z"
        }

        override fun loadItems(): List<WbRepository.WbItem> {
            calls += "loadItems"
            return listOf(
                WbRepository.WbItem(
                    nameEn = "Ironclad",
                    nameZh = "铁甲舰",
                    warbondPrice = 525.0,
                    standardPrice = 600.0,
                    currency = "USD",
                    url = "https://example.test/ironclad",
                    thumbnail = null,
                ),
            )
        }
    }

    private class RecordingHangarProvider : SyncableHangarTimerSnapshotProvider {
        val calls = mutableListOf<String>()

        override fun sync(): AppToolSyncResult {
            calls += "sync"
            return AppToolSyncResult(success = true, message = "已同步：test-source")
        }

        override fun snapshot(): HangarTimerSnapshot {
            calls += "snapshot"
            return HangarTimerSnapshot(
                phaseLabel = "机库开启",
                isOpen = true,
                lights = listOf("green", "green", "green", "green", "green"),
                remainingSeconds = 300,
                nextOpenAtSeconds = 1_800,
                source = "已校准",
                anchorAtSeconds = 1_000,
            )
        }
    }

    private class FakeRsiInventoryProvider : RsiInventoryProvider {
        override fun lastSync(): String = "2026-06-18 11:20:00"

        override fun shipAliases(): Map<String, String> = mapOf(
            "Terrapin" to "陆龟",
            "Railen" to "瑞伦",
            "Gladius" to "短剑",
            "Hawk" to "鹰",
        )

        override fun loadItems(): List<InventoryItem> = listOf(
            inventoryItem(
                name = "Upgrade - Terrapin to Railen Warbond Edition",
                priceCents = 500,
                page = 5,
            ),
            inventoryItem(
                name = "Upgrade - Gladius to Hawk Standard Edition",
                priceCents = 500,
                page = 12,
            ),
            inventoryItem(
                name = "Railen Paint Pack",
                priceCents = 1100,
                page = 3,
            ),
        )
    }

    private class LargeRsiInventoryProvider : RsiInventoryProvider {
        override fun lastSync(): String = "2026-06-18 11:20:00"
        override fun shipAliases(): Map<String, String> = emptyMap()
        override fun loadItems(): List<InventoryItem> =
            (1..60).map { index ->
                inventoryItem(
                    name = "Standalone Ship $index",
                    priceCents = index * 100,
                    page = index,
                )
            }
    }

    private class RecordingRsiStatusProvider : SyncableRsiServerStatusProvider {
        val calls = mutableListOf<String>()

        override fun sync(): AppToolSyncResult {
            calls += "sync"
            return AppToolSyncResult(success = true, message = "已同步 RSI 状态")
        }

        override fun snapshot(): RsiServerStatusSnapshot {
            calls += "snapshot"
            return RsiServerStatusSnapshot(
                status = ToolHeaderStatus(
                    platform = ServiceStatusLevel.OPERATIONAL,
                    persistentUniverse = ServiceStatusLevel.DEGRADED,
                    arenaCommander = ServiceStatusLevel.OUTAGE,
                ),
                updatedAt = 1_787_000_000_000L,
                source = "remote",
            )
        }
    }

    @Test
    fun dailyWbToolSyncsBeforeReadingItems() = runBlocking {
        val provider = RecordingDailyWbProvider()
        val tool = DailyWbTool(provider)

        val result = tool.run(AgentToolCall("get_daily_wb", mapOf("query" to "铁甲")))

        assertEquals(listOf("sync", "loadItems", "generatedAt"), provider.calls)
        assertTrue(result.summary.contains("已同步每日 WB"))
        assertTrue(result.facts.any { it.label == "同步状态" && it.value.contains("已同步每日 WB") })
    }

    @Test
    fun dailyWbToolReturnsWarbondItems() = runBlocking {
        val tool = DailyWbTool(
            provider = object : DailyWbProvider {
                override fun generatedAt(): String = "2026-06-09T04:55:43Z"
                override fun loadItems(): List<WbRepository.WbItem> = listOf(
                    WbRepository.WbItem(
                        nameEn = "Ironclad",
                        nameZh = "铁甲舰",
                        warbondPrice = 525.0,
                        standardPrice = 600.0,
                        currency = "USD",
                        url = "https://example.test/ironclad",
                        thumbnail = null,
                    ),
                )
            },
        )

        val result = tool.run(AgentToolCall("get_daily_wb", mapOf("query" to "铁甲")))

        assertTrue(result.summary.contains("铁甲舰 (Ironclad)"))
        assertTrue(result.summary.contains("WB USD 525"))
        assertTrue(result.summary.contains("原价 USD 600"))
        assertTrue(result.facts.any { it.label == "更新于" && it.value == "2026-06-09T04:55:43Z" })
        assertEquals(0.74f, result.confidence)
    }

    @Test
    fun hangarTimerToolSyncsBeforeReadingSnapshot() = runBlocking {
        val provider = RecordingHangarProvider()
        val tool = HangarTimerTool(provider)

        val result = tool.run(AgentToolCall("get_hangar_timer", emptyMap()))

        assertEquals(listOf("sync", "snapshot"), provider.calls)
        assertTrue(result.summary.contains("已同步"))
        assertTrue(result.facts.any { it.label == "同步状态" && it.value.contains("已同步") })
    }

    @Test
    fun hangarTimerToolReturnsCurrentPhaseAndNextOpenTime() = runBlocking {
        val tool = HangarTimerTool(
            provider = object : HangarTimerSnapshotProvider {
                override fun snapshot(): HangarTimerSnapshot = HangarTimerSnapshot(
                    phaseLabel = "机库关闭",
                    isOpen = false,
                    lights = listOf("red", "red", "green", "green", "green"),
                    remainingSeconds = 120,
                    nextOpenAtSeconds = 1_800,
                    source = "已校准",
                    anchorAtSeconds = 1_000,
                )
            },
        )

        val result = tool.run(AgentToolCall("get_hangar_timer", emptyMap()))

        assertTrue(result.summary.contains("机库关闭"))
        assertTrue(result.summary.contains("00:02:00"))
        assertTrue(result.summary.contains("下一次开启"))
        assertTrue(result.facts.any { it.label == "灯状态" && it.value.contains("red") })
    }

    @Test
    fun rsiServerStatusToolSyncsAndReturnsServiceLevels() = runBlocking {
        val provider = RecordingRsiStatusProvider()
        val tool = RsiServerStatusTool(provider)

        val result = tool.run(AgentToolCall("get_rsi_server_status", emptyMap()))

        assertEquals(listOf("sync", "snapshot"), provider.calls)
        assertTrue(result.summary.contains("已同步 RSI 状态"))
        assertTrue(result.summary.contains("Platform：正常"))
        assertTrue(result.summary.contains("Persistent Universe：降级"))
        assertTrue(result.summary.contains("Arena Commander：停机"))
        assertTrue(result.facts.any { it.label == "Persistent Universe" && it.value == "降级" })
        assertEquals(0.78f, result.confidence)
    }

    @Test
    fun capabilitiesToolListsCurrentAppCapabilities() = runBlocking {
        val tool = AppCapabilitiesTool()

        val result = tool.run(AgentToolCall("list_app_capabilities", mapOf("query" to "签到")))

        assertTrue(result.summary.contains("SCM 签到"))
        assertTrue(result.summary.contains("scm_sign_in"))
        assertTrue(result.facts.any { it.label == "能力" && it.value.contains("SCM 签到") })
    }

    @Test
    fun capabilitiesToolListsMyOrdersCapability() = runBlocking {
        val tool = AppCapabilitiesTool()

        val result = tool.run(AgentToolCall("list_app_capabilities", mapOf("query" to "我的挂单")))

        assertTrue(result.summary.contains("SCM 我的挂单"))
        assertTrue(result.summary.contains("list_my_orders"))
        assertTrue(result.facts.any { it.label == "能力" && it.value.contains("我的挂单") })
    }

    @Test
    fun capabilitiesToolListsRsiServerStatusCapability() = runBlocking {
        val tool = AppCapabilitiesTool()

        val result = tool.run(AgentToolCall("list_app_capabilities", mapOf("query" to "服务器状态")))

        assertTrue(result.summary.contains("RSI 服务器状态"))
        assertTrue(result.summary.contains("get_rsi_server_status"))
        assertTrue(result.facts.any { it.label == "能力" && it.value.contains("服务器状态") })
    }

    @Test
    fun rsiInventoryToolReturnsTranslatedWarbondCcus() = runBlocking {
        val tool = RsiInventoryTool(FakeRsiInventoryProvider())

        val result = tool.run(
            AgentToolCall(
                "get_rsi_inventory",
                mapOf("type" to "ccu", "query" to "瑞伦"),
            ),
        )

        assertTrue(result.summary.contains("陆龟 -> 瑞伦"))
        assertTrue(result.summary.contains("Terrapin -> Railen"))
        assertTrue(result.summary.contains("CCU / WB / 价格 $5.00"))
        assertTrue(result.facts.any { it.label == "WB" && it.value == "陆龟 -> 瑞伦" })
        assertEquals(0.72f, result.confidence)
    }

    @Test
    fun rsiInventoryToolCanFilterPaintItems() = runBlocking {
        val tool = RsiInventoryTool(FakeRsiInventoryProvider())

        val result = tool.run(AgentToolCall("get_rsi_inventory", mapOf("type" to "paint")))

        assertTrue(result.summary.contains("Railen Paint Pack"))
        assertTrue(result.summary.contains("皮肤"))
    }

    @Test
    fun rsiInventoryToolAllowsLargeExplicitLimitAndReportsRemainingItems() = runBlocking {
        val tool = RsiInventoryTool(LargeRsiInventoryProvider())

        val result = tool.run(AgentToolCall("get_rsi_inventory", mapOf("limit" to "50")))

        assertTrue(result.summary.contains("Standalone Ship 50"))
        assertTrue(!result.summary.contains("Standalone Ship 51"))
        assertTrue(result.summary.contains("还有 10 项未显示"))
    }

    private companion object {
        fun inventoryItem(
            name: String,
            priceCents: Int,
            page: Int,
        ): InventoryItem = InventoryItem(
            id = "$page-$name",
            name = name,
            priceCents = priceCents,
            currentPriceCents = priceCents,
            status = "Attributed",
            date = "2026年06月18日",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = page,
            canGift = true,
            canReclaim = true,
            canUpgrade = false,
        )
    }
}
