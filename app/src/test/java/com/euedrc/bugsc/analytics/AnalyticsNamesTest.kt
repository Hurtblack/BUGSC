package com.euedrc.bugsc.analytics

import com.euedrc.bugsc.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsNamesTest {

    @Test
    fun mapsMainNavigationDestinationsToStablePageNames() {
        val expected = mapOf(
            R.id.ToolsFragment to "tools",
            R.id.NewsFragment to "news",
            R.id.QueryFragment to "query",
            R.id.ProfileFragment to "profile",
            R.id.AgentChatFragment to "agent",
            R.id.AgentSettingsFragment to "agent_settings",
            R.id.MarketFragment to "market",
            R.id.MarketDetailFragment to "market_detail",
            R.id.MarketOrderEditFragment to "market_order_edit",
            R.id.MyMarketOrdersFragment to "my_market_orders",
            R.id.TransactionListFragment to "transactions",
            R.id.TransactionDetailFragment to "transaction_detail",
            R.id.ChatConversationListFragment to "chat_conversations",
            R.id.ChatFragment to "chat",
            R.id.BlueprintFragment to "blueprint",
            R.id.MissionQueryFragment to "mission_query",
            R.id.ShipFitFragment to "ship_fit",
            R.id.ShipLoadoutFragment to "ship_loadout",
            R.id.WikeloFragment to "wikelo",
            R.id.MiningFragment to "mining",
            R.id.InventoryFragment to "inventory",
            R.id.WbFragment to "daily_wb",
            R.id.HangarTimerFragment to "hangar_timer",
            R.id.CharacterRepairFragment to "character_repair",
            R.id.BugListFragment to "bug_list",
            R.id.BugDetailFragment to "bug_detail",
            R.id.BugSubmitFragment to "bug_submit",
            R.id.IssueCouncilFragment to "issue_council",
            R.id.RsiLoginFragment to "rsi_login",
            R.id.ScmLoginFragment to "scm_login",
            R.id.ScmRegisterFragment to "scm_register",
            R.id.ScmPasswordFragment to "scm_password",
            R.id.LegalFragment to "legal",
        )

        assertEquals(expected, expected.keys.associateWith { AnalyticsNames.pageNameForDestination(it) })
    }
}
