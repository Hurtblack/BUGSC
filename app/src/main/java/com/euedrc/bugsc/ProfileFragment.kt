package com.euedrc.bugsc

import android.content.Context
import android.graphics.BitmapFactory
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
import com.euedrc.bugsc.chat.ChatUnreadStore
import com.euedrc.bugsc.scm.AppMemberUserInfoRespVO
import com.euedrc.bugsc.scm.ScmAuthStore
import com.euedrc.bugsc.scm.ScmClient
import com.euedrc.bugsc.scm.ScmPasswordFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 底部栏「个人信息」落地页：RSI 账号卡 + SCM 账号卡 + 关于卡。 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRsiLogin.setOnClickListener {
            findNavController().navigate(R.id.RsiLoginFragment, bundleOf(ARG_RETURN_DEST to 0))
        }
        binding.btnRsiLogout.setOnClickListener { logoutRsi() }
        binding.btnRsiCopy.setOnClickListener { copyRsiReferral() }

        binding.btnScmLogin.setOnClickListener {
            findNavController().navigate(R.id.ScmLoginFragment, bundleOf(ARG_RETURN_DEST to 0))
        }
        binding.btnScmRegister.setOnClickListener {
            findNavController().navigate(R.id.ScmRegisterFragment, bundleOf(ARG_RETURN_DEST to 0))
        }
        binding.btnScmChangepw.setOnClickListener {
            findNavController().navigate(R.id.ScmPasswordFragment, bundleOf("mode" to ScmPasswordFragment.MODE_CHANGE))
        }
        binding.btnScmLogout.setOnClickListener { logoutScm() }
        binding.btnScmSignin.setOnClickListener { doSignIn() }
        binding.btnScmTransactions.setOnClickListener {
            findNavController().navigate(R.id.TransactionListFragment)
        }
        binding.btnScmMessages.setOnClickListener {
            findNavController().navigate(R.id.ChatConversationListFragment)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ChatUnreadStore.count.collect { count ->
                if (_binding != null) {
                    binding.btnScmMessages.text = if (count > 0) "消息 ($count)" else "消息"
                }
            }
        }

        binding.tvVersion.text = "v${currentVersionName()}"
        binding.rowPrivacy.setOnClickListener { navigateLegal(LegalDocs.PRIVACY, "隐私政策") }
        binding.rowAgreement.setOnClickListener { navigateLegal(LegalDocs.AGREEMENT, "用户协议") }
        binding.rowDisclaimer.setOnClickListener { navigateLegal(LegalDocs.DISCLAIMER, "免责声明") }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }
    }

    override fun onResume() {
        super.onResume()
        // 从登录/登出页返回后刷新两张卡。
        renderRsiCard()
        renderScmCard()
    }

    // ---- RSI 账号卡 ----

    private fun renderRsiCard() {
        val prefs = requireContext().getSharedPreferences("inventory", Context.MODE_PRIVATE)
        val loggedIn = RsiCookieStore.loadSession(requireContext()).isLoggedIn
        binding.btnRsiLogin.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnRsiLogout.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.containerRsiProfile.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.tvRsiLoggedOut.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.containerRsiReferral.visibility = View.GONE
        if (!loggedIn) return

        // 先显示缓存
        val name = prefs.getString("inventoryProfileName", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("inventoryUsername", "")?.takeIf { it.isNotBlank() }
            ?: "RSI 账号"
        val handle = prefs.getString("inventoryProfileHandle", "").orEmpty()
        binding.tvRsiName.text = name
        binding.tvRsiHandle.text = if (handle.isNotBlank()) "@$handle" else ""
        loadAvatarInto(prefs.getString("inventoryProfileAvatar", "").orEmpty(), binding.ivRsiAvatar)
        bindRsiReferral(prefs.getString("inventoryReferralCode", "").orEmpty())

        // 拉取最新资料（含邀请码）
        val session = RsiSession(
            token = prefs.getString("inventoryRsiToken", "") ?: "",
            device = prefs.getString("inventoryRsiDevice", "") ?: "",
            accountAuth = prefs.getString("inventoryRsiAccountAuth", "") ?: "",
            upgradeContext = prefs.getString("inventoryRsiUpgradeContext", "") ?: "",
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { RsiInventoryClient(session).fetchUserProfile() }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch
            prefs.edit()
                .putString("inventoryProfileName", profile.displayName)
                .putString("inventoryProfileHandle", profile.handle)
                .putString("inventoryProfileAvatar", profile.avatarUrl)
                .putString("inventoryReferralCode", profile.referralCode)
                .apply()
            if (profile.displayName.isNotBlank()) binding.tvRsiName.text = profile.displayName
            if (profile.handle.isNotBlank()) binding.tvRsiHandle.text = "@${profile.handle}"
            loadAvatarInto(profile.avatarUrl, binding.ivRsiAvatar)
            bindRsiReferral(profile.referralCode)
        }
    }

    private fun bindRsiReferral(code: String) {
        if (code.isNotBlank()) {
            binding.tvRsiReferral.text = code
            binding.containerRsiReferral.visibility = View.VISIBLE
        } else {
            binding.containerRsiReferral.visibility = View.GONE
        }
    }

    private fun copyRsiReferral() {
        val code = binding.tvRsiReferral.text?.toString().orEmpty()
        if (code.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RSI 邀请码", code))
        toast("已复制邀请码：$code")
    }

    private fun logoutRsi() {
        requireContext().getSharedPreferences("inventory", Context.MODE_PRIVATE).edit().clear().apply()
        renderRsiCard()
        toast("已退出 RSI 登录")
    }

    // ---- SCM 账号卡 ----

    private fun renderScmCard() {
        val loggedIn = ScmAuthStore.isLoggedIn
        binding.btnScmLogin.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnScmRegister.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnScmChangepw.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.btnScmLogout.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.containerScmProfile.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.containerScmStats.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.tvScmLoggedOut.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.tvScmMark.visibility = View.GONE
        binding.containerScmSignin.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.containerScmBusiness.visibility = if (loggedIn) View.VISIBLE else View.GONE

        if (!loggedIn) return

        val userId = ScmAuthStore.session().userId
        val prefs = scmProfilePrefs()
        val cached = prefs.getString(scmProfileKey(userId), null)
            ?.let(AppMemberUserInfoRespVO::parseCache)
        if (cached != null) {
            bindScmInfo(cached)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { ScmClient.getUserInfo() }.getOrNull() }
            if (_binding == null) return@launch
            if (info == null) {
                // 401 走 refresh 仍失败 → 视为未登录
                if (!ScmAuthStore.isLoggedIn) renderScmCard()
                return@launch
            }
            if (info != cached) {
                prefs.edit().putString(scmProfileKey(info.id), info.toCacheJson()).apply()
                bindScmInfo(info)
            }
        }
        loadSignInSummary()
    }

    private fun scmProfilePrefs() =
        requireContext().getSharedPreferences("scm_profile", Context.MODE_PRIVATE)

    private fun scmProfileKey(userId: Long): String = "profile_$userId"

    private fun loadSignInSummary() {
        binding.tvScmSignin.text = "签到信息加载中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { ScmClient.signInSummary() }.getOrNull() }
            if (_binding == null) return@launch
            if (s == null) {
                binding.tvScmSignin.text = "连续签到 —"
                binding.btnScmSignin.isEnabled = true
                binding.btnScmSignin.text = "签到"
                return@launch
            }
            binding.tvScmSignin.text = "连续签到 ${s.continuousDay} 天   ·   累计 ${s.totalDay} 天"
            if (s.todaySignIn) {
                binding.btnScmSignin.isEnabled = false
                binding.btnScmSignin.text = "已签到"
            } else {
                binding.btnScmSignin.isEnabled = true
                binding.btnScmSignin.text = "签到"
            }
        }
    }

    private fun doSignIn() {
        binding.btnScmSignin.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScmClient.signIn() }.getOrElse { com.euedrc.bugsc.scm.ScmResult(false, it.message ?: "网络错误", -1) }
            }
            if (_binding == null) return@launch
            if (result.success) {
                toast("签到成功")
            } else {
                toast(result.msg.ifBlank { "签到失败" })
                binding.btnScmSignin.isEnabled = true
            }
            loadSignInSummary()
        }
    }

    private fun bindScmInfo(info: AppMemberUserInfoRespVO) {
        binding.tvScmNickname.text = info.nickname.ifBlank { "SCM 用户" }
        binding.tvScmEmail.text = info.email.ifBlank { "ID: ${info.id}" }

        if (info.mark.isNotBlank()) {
            binding.tvScmMark.text = info.mark
            binding.tvScmMark.visibility = View.VISIBLE
        } else {
            binding.tvScmMark.visibility = View.GONE
        }

        val rsi = when (info.rsiAccurate) {
            1 -> "有效"
            0 -> "失效"
            else -> "未验证"
        }
        val rows = binding.containerScmStats
        rows.removeAllViews()
        addStatRow(rows, "信誉积分", "${info.reputationPoint}")
        addStatRow(rows, "订单上限", "${info.orderLimit}")
        addStatRow(rows, "赞助等级", "L${info.sponsorLevel}")
        addStatRow(rows, "RSI 账号", rsi)
        addStatRow(rows, "出售订单", "${info.sellOrderCount}")
        addStatRow(rows, "收购订单", "${info.buyOrderCount}")
        if (info.groups.isNotEmpty()) addStatRow(rows, "分组", info.groups.joinToString(" · "))
        if (info.organization.isNotBlank()) addStatRow(rows, "所属舰队", info.organization)
        if (info.createTime > 0) addStatRow(rows, "注册时间", formatDate(info.createTime))

        loadAvatarInto(info.avatar, binding.ivScmAvatar)
    }

    /** 一行键值：左淡色标签，右亮色加粗数值。 */
    private fun addStatRow(container: android.widget.LinearLayout, label: String, value: String) {
        val ctx = container.context
        val density = resources.displayMetrics.density
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val v = (6 * density).toInt()
            setPadding(0, v, 0, v)
        }
        val labelView = android.widget.TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.sc_text_mid))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = android.widget.TextView(ctx).apply {
            text = value
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.sc_text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
        }
        row.addView(labelView)
        row.addView(valueView)
        container.addView(row)
    }

    /** createTime 为秒级时间戳（兼容毫秒）。 */
    private fun formatDate(ts: Long): String {
        val millis = if (ts < 100_000_000_000L) ts * 1000 else ts
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }

    private fun loadAvatarInto(url: String, target: android.widget.ImageView) {
        if (url.isBlank()) return
        target.tag = url
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Referer", "https://robertsspaceindustries.com/")
                    val code = conn.responseCode
                    if (code !in 200..299) return@runCatching null
                    try {
                        BitmapFactory.decodeStream(conn.inputStream)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (_binding != null && target.tag == url && bitmap != null) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun logoutScm() {
        binding.btnScmLogout.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { ScmClient.logout() } }
            if (_binding == null) return@launch
            binding.btnScmLogout.isEnabled = true
            ChatUnreadStore.clear()
            scmProfilePrefs().edit().clear().apply()
            renderScmCard()
            toast("已退出 SCM 登录")
        }
    }

    // ---- 关于 ----

    private fun checkForUpdates() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "检查中..."
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdateClient().fetchLatestRelease() }
            }
            if (_binding == null) return@launch
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "检查更新"
            result.onSuccess { release ->
                if (release == null) { toast("未获取到可用版本信息"); return@onSuccess }
                val currentVersion = currentVersionName()
                if (!AppUpdateClient.isNewerVersion(currentVersion, release.versionName)) {
                    toast("当前已是最新版本"); return@onSuccess
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
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull().orEmpty()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
