package com.euedrc.bugsc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.databinding.FragmentToolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部栏「工具」落地页 */
class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private val statusClient = RsiStatusClient()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardBug.setOnClickListener {
            findNavController().navigate(R.id.BugListFragment)
        }
        binding.cardTimer.setOnClickListener {
            findNavController().navigate(R.id.HangarTimerFragment)
        }
        binding.cardWb.setOnClickListener {
            findNavController().navigate(R.id.WbFragment)
        }
        binding.cardComing.setOnClickListener {
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
        _binding = null
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
        binding.tvStatusPlatformDot.setTextColor(statusColor(status.platform))
        binding.tvStatusPuDot.setTextColor(statusColor(status.persistentUniverse))
        binding.tvStatusAcDot.setTextColor(statusColor(status.arenaCommander))
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
