package com.euedrc.bugsc.analytics

import com.euedrc.bugsc.R

object AnalyticsNames {
    fun pageNameForDestination(destId: Int): String? = when (destId) {
        R.id.ToolsFragment -> "tools"
        R.id.NewsFragment -> "news"
        R.id.QueryFragment -> "query"
        R.id.ProfileFragment -> "profile"
        R.id.AgentChatFragment -> "agent"
        R.id.AgentSettingsFragment -> "agent_settings"
        R.id.MarketFragment -> "market"
        R.id.MarketDetailFragment -> "market_detail"
        R.id.MarketOrderEditFragment -> "market_order_edit"
        R.id.MyMarketOrdersFragment -> "my_market_orders"
        R.id.TransactionListFragment -> "transactions"
        R.id.TransactionDetailFragment -> "transaction_detail"
        R.id.ChatConversationListFragment -> "chat_conversations"
        R.id.ChatFragment -> "chat"
        R.id.BlueprintFragment -> "blueprint"
        R.id.MissionQueryFragment -> "mission_query"
        R.id.ShipFitFragment -> "ship_fit"
        R.id.ShipLoadoutFragment -> "ship_loadout"
        R.id.WikeloFragment -> "wikelo"
        R.id.MiningFragment -> "mining"
        R.id.InventoryFragment -> "inventory"
        R.id.WbFragment -> "daily_wb"
        R.id.HangarTimerFragment -> "hangar_timer"
        R.id.CharacterRepairFragment -> "character_repair"
        R.id.BugListFragment -> "bug_list"
        R.id.BugDetailFragment -> "bug_detail"
        R.id.BugSubmitFragment -> "bug_submit"
        R.id.IssueCouncilFragment -> "issue_council"
        R.id.RsiLoginFragment -> "rsi_login"
        R.id.ScmLoginFragment -> "scm_login"
        R.id.ScmRegisterFragment -> "scm_register"
        R.id.ScmPasswordFragment -> "scm_password"
        R.id.LegalFragment -> "legal"
        else -> null
    }
}
