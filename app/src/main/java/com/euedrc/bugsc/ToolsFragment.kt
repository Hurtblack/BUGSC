package com.euedrc.bugsc

import android.content.Intent
import android.net.Uri
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
    private var cardBug: LinearLayout? = null
    private var cardTimer: LinearLayout? = null
    private var cardWb: LinearLayout? = null
    private var cardComing: LinearLayout? = null
    private var tvStatusPlatformDot: TextView? = null
    private var tvStatusPuDot: TextView? = null
    private var tvStatusAcDot: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cardBug = view.findViewById(R.id.card_bug)
        cardTimer = view.findViewById(R.id.card_timer)
        cardWb = view.findViewById(R.id.card_wb)
        cardComing = view.findViewById(R.id.card_coming)
        tvStatusPlatformDot = view.findViewById(R.id.tv_status_platform_dot)
        tvStatusPuDot = view.findViewById(R.id.tv_status_pu_dot)
        tvStatusAcDot = view.findViewById(R.id.tv_status_ac_dot)

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
        cardComing?.setOnClickListener {
            AnalyticsTracker.get(requireContext()).trackFeatureClick("tools", "more_tools")
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SCM_TOOLS_URL)))
            }.onFailure {
                Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        loadServerStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cardBug = null
        cardTimer = null
        cardWb = null
        cardComing = null
        tvStatusPlatformDot = null
        tvStatusPuDot = null
        tvStatusAcDot = null
    }

    companion object {
        /** SCM 网站工具栏（合作方维护） */
        private const val SCM_TOOLS_URL = "https://flowcld.xyz/tools"
    }

    private fun loadServerStatus() {
        renderServerStatus(ToolHeaderStatus())
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { statusClient.fetchStatus() }
            }
            result.onSuccess { renderServerStatus(it) }
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
            ServiceStatusLevel.DEGRADED -> R.color.sc_warn
            ServiceStatusLevel.OUTAGE -> R.color.sc_danger
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }
}
