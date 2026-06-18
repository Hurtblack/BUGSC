package com.euedrc.bugsc.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolIntentsTest {

    @Test
    fun signInRequestResolvesToSignAction() {
        val call = AgentToolIntents.deterministicCall("帮我签到")

        assertEquals("scm_sign_in", call?.tool)
        assertEquals("sign", call?.args?.get("action"))
        assertEquals("deterministic_scm_sign_in", call?.reason)
    }

    @Test
    fun signInStatusQueryResolvesToStatusAction() {
        val call = AgentToolIntents.deterministicCall("我签到了吗")

        assertEquals("scm_sign_in", call?.tool)
        assertEquals("status", call?.args?.get("action"))
    }

    @Test
    fun myOrdersQueryResolvesWithOptionalSide() {
        assertEquals("list_my_orders", AgentToolIntents.deterministicCall("我有什么订单")?.tool)
        assertEquals("deterministic_my_orders", AgentToolIntents.deterministicCall("我的挂单")?.reason)
        assertEquals("sell", AgentToolIntents.deterministicCall("我的出售挂单")?.args?.get("side"))
        assertEquals("buy", AgentToolIntents.deterministicCall("我的求购挂单")?.args?.get("side"))
    }

    @Test
    fun manageMyOrderRequestResolvesBeforeMyOrdersQuery() {
        val hide = AgentToolIntents.deterministicCall("帮我下架订单 OWN-1")
        assertEquals("manage_my_order", hide?.tool)
        assertEquals("hide", hide?.args?.get("action"))
        assertEquals("OWN-1", hide?.args?.get("orderNumber"))

        val delete = AgentToolIntents.deterministicCall("删除我的订单 OWN-2")
        assertEquals("manage_my_order", delete?.tool)
        assertEquals("delete", delete?.args?.get("action"))
        assertEquals("OWN-2", delete?.args?.get("orderNumber"))
    }

    @Test
    fun myTransactionsQueryResolvesToMarketActivity() {
        assertEquals("list_my_market_activity", AgentToolIntents.deterministicCall("查我的交易")?.tool)
        assertEquals("list_my_market_activity", AgentToolIntents.deterministicCall("我卖出的单")?.tool)
    }

    @Test
    fun rsiInventoryQueryResolvesToInventoryTool() {
        val call = AgentToolIntents.deterministicCall("帮我看我的 WBCCU 怎么规划")

        assertEquals("get_rsi_inventory", call?.tool)
        assertEquals("ccu", call?.args?.get("type"))
        assertEquals("deterministic_rsi_inventory", call?.reason)
    }

    @Test
    fun rsiServerStatusQueryResolvesToStatusTool() {
        val call = AgentToolIntents.deterministicCall("现在星际公民服务器状态怎么样，PU 维护了吗")

        assertEquals("get_rsi_server_status", call?.tool)
        assertEquals("deterministic_rsi_server_status", call?.reason)
    }

    @Test
    fun createOrderRequestIsNotCapturedByMyOrdersFallback() {
        // "我要卖/发布订单" 应交给 draft_scm_order，而不是被 "我的订单" 兜底误捕。
        assertNull(AgentToolIntents.deterministicCall("我要卖绝杀步枪"))
        assertNull(AgentToolIntents.deterministicCall("帮我发布订单"))
    }

    @Test
    fun signInTakesPrecedenceOverCreateOrderExclusion() {
        // 签到判定在 "创建/挂单" 排除之前，即使句子里含挂单字样也应签到。
        assertEquals("scm_sign_in", AgentToolIntents.deterministicCall("签到")?.tool)
    }

    @Test
    fun casualQueryHasNoDeterministicFallback() {
        assertNull(AgentToolIntents.deterministicCall("C2 货仓多大"))
        assertNull(AgentToolIntents.deterministicCall("你好啊"))
    }

    @Test
    fun promptHintsCoverEveryRuleAsSingleSource() {
        // prompt 引导与兜底来自同一份 rules，保证不会漂移。
        assertEquals(AgentToolIntents.rules.size, AgentToolIntents.promptHints.size)
        assertTrue(AgentToolIntents.promptHints.all { it.contains("常见判断") })
        assertTrue(AgentToolIntents.promptHints.any { it.contains("search_market") })
        assertTrue(AgentToolIntents.promptHints.any { it.contains("scm_sign_in") })
        assertTrue(AgentToolIntents.promptHints.any { it.contains("get_rsi_inventory") })
        assertTrue(AgentToolIntents.promptHints.any { it.contains("get_rsi_server_status") })
    }
}
