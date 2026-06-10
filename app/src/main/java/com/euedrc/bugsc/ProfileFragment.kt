package com.euedrc.bugsc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.databinding.FragmentProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部栏「个人信息」落地页 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardInventory.setOnClickListener {
            findNavController().navigate(R.id.InventoryFragment)
        }
        binding.cardRepair.setOnClickListener {
            findNavController().navigate(R.id.CharacterRepairFragment)
        }
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        binding.tvVersion.text = "v${currentVersionName()}"
        binding.rowPrivacy.setOnClickListener { navigateLegal(LegalDocs.PRIVACY, "隐私政策") }
        binding.rowAgreement.setOnClickListener { navigateLegal(LegalDocs.AGREEMENT, "用户协议") }
        binding.rowDisclaimer.setOnClickListener { navigateLegal(LegalDocs.DISCLAIMER, "免责声明") }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "检查中..."
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdateClient().fetchLatestRelease() }
            }
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "检查更新"
            result.onSuccess { release ->
                if (release == null) {
                    toast("未获取到可用版本信息")
                    return@onSuccess
                }
                val currentVersion = currentVersionName()
                if (!AppUpdateClient.isNewerVersion(currentVersion, release.versionName)) {
                    toast("当前已是最新版本")
                    return@onSuccess
                }
                AppUpdateNotifier.showUpdateDialog(requireContext(), currentVersion, release)
            }.onFailure {
                toast("检查更新失败：${it.message ?: "网络错误"}")
            }
        }
    }

    private fun navigateLegal(doc: String, title: String) {
        findNavController().navigate(
            R.id.LegalFragment,
            bundleOf(LegalFragment.ARG_DOC to doc, LegalFragment.ARG_TITLE to title),
        )
    }

    private fun currentVersionName(): String =
        runCatching {
            requireContext()
                .packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName
        }.getOrNull().orEmpty()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
