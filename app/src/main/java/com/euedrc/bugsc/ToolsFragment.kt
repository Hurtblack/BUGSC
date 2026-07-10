package com.euedrc.bugsc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.analytics.AnalyticsTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部栏「工具」落地页 */
class ToolsFragment : Fragment() {

    private val statusClient = RsiStatusClient()
    private lateinit var statusCache: RsiStatusCache
    private var currentHeaderStatus: ToolHeaderStatus? = null
    private var cardBug: LinearLayout? = null
    private var cardAgent: LinearLayout? = null
    private var cardTimer: LinearLayout? = null
    private var cardWb: LinearLayout? = null
    private var cardWbccuChain: LinearLayout? = null
    private var cardRepair: LinearLayout? = null
    private var cardComing: LinearLayout? = null
    private var tvStatusPlatformDot: TextView? = null
    private var tvStatusPuDot: TextView? = null
    private var tvStatusAcDot: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cardAgent = view.findViewById(R.id.card_agent)
        cardBug = view.findViewById(R.id.card_bug)
        cardTimer = view.findViewById(R.id.card_timer)
        cardWb = view.findViewById(R.id.card_wb)
        cardWbccuChain = view.findViewById(R.id.card_wbccu_chain)
        cardRepair = view.findViewById(R.id.card_repair)
        cardComing = view.findViewById(R.id.card_coming)
        tvStatusPlatformDot = view.findViewById(R.id.tv_status_platform_dot)
        tvStatusPuDot = view.findViewById(R.id.tv_status_pu_dot)
        tvStatusAcDot = view.findViewById(R.id.tv_status_ac_dot)
        statusCache = RsiStatusCache(requireContext())

        cardAgent?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "agent_assistant")
            findNavController().navigate(R.id.AgentChatFragment)
        }
        cardBug?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "bug_list")
            findNavController().navigate(R.id.BugListFragment)
        }
        cardTimer?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "hangar_timer")
            findNavController().navigate(R.id.HangarTimerFragment)
        }
        cardWb?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "daily_wb")
            findNavController().navigate(R.id.WbFragment)
        }
        cardWbccuChain?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "wbccu_chain")
            findNavController().navigate(R.id.WbccuChainFragment)
        }
        cardRepair?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "character_repair")
            findNavController().navigate(R.id.CharacterRepairFragment)
        }
        cardComing?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "more_tools")
            Toast.makeText(requireContext(), "当前构建未启用 SCM 网站工具栏", Toast.LENGTH_SHORT).show()
        }

        loadServerStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cardAgent = null
        cardBug = null
        cardTimer = null
        cardWb = null
        cardWbccuChain = null
        cardRepair = null
        cardComing = null
        tvStatusPlatformDot = null
        tvStatusPuDot = null
        tvStatusAcDot = null
    }

    private fun loadServerStatus() {
        val cached = statusCache.load()?.status ?: ToolHeaderStatus()
        currentHeaderStatus = cached
        renderServerStatus(cached)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { statusClient.fetchStatus() }
            }
            result.onSuccess { fresh ->
                if (fresh != currentHeaderStatus) {
                    currentHeaderStatus = fresh
                    renderServerStatus(fresh)
                }
                statusCache.save(fresh)
            }
        }
    }

    private fun renderServerStatus(status: ToolHeaderStatus) {
        tvStatusPlatformDot?.setTextColor(statusColor(status.platform))
        tvStatusPuDot?.setTextColor(statusColor(status.persistentUniverse))
        tvStatusAcDot?.setTextColor(statusColor(status.arenaCommander))
    }

    private fun statusColor(level: ServiceStatusLevel): Int {
        val colorRes = when (level) {
            ServiceStatusLevel.OPERATIONAL -> R.color.sc_ok
            ServiceStatusLevel.DEGRADED -> R.color.sc_purple
            ServiceStatusLevel.OUTAGE -> R.color.sc_danger
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }
}
