package com.euedrc.bugsc.agent

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
    fun capabilitiesToolListsCurrentAppCapabilities() = runBlocking {
        val tool = AppCapabilitiesTool()

        val result = tool.run(AgentToolCall("list_app_capabilities", mapOf("query" to "wb")))

        assertTrue(result.summary.contains("每日 WB"))
        assertTrue(result.summary.contains("get_daily_wb"))
        assertTrue(result.facts.any { it.label == "能力" && it.value.contains("每日 WB") })
    }
}
