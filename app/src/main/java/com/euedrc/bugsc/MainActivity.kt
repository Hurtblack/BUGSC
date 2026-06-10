package com.euedrc.bugsc

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.euedrc.bugsc.ui.MobiGlasBottomBar
import com.euedrc.bugsc.ui.MobiGlasItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "scmobiglas_prefs"
        private const val KEY_CONSENT_VERSION = "legal_consent_version"
        private const val KEY_IGNORED_VERSION = "ignored_update_version"

        /** 协议版本号：协议有重大变更时 +1，触发重新征求同意 */
        private const val LEGAL_VERSION = 1
    }

    // 底部栏三个顶层目的地，顺序与 items 一致
    private val topLevel = listOf(R.id.ToolsFragment, R.id.QueryFragment, R.id.ProfileFragment)

    private val items = listOf(
        MobiGlasItem("工具", Icons.Filled.Build),
        MobiGlasItem("查询", Icons.Filled.Search),
        MobiGlasItem("个人信息", Icons.Filled.Person),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        val composeView = findViewById<ComposeView>(R.id.bottom_bar)

        // 选中项 & 显隐由目的地变化驱动
        var selectedIndex by mutableIntStateOf(0)
        var barVisible by mutableStateOf(true)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val idx = topLevel.indexOf(destination.id)
            if (idx >= 0) {
                selectedIndex = idx
                barVisible = true
            } else {
                // 钻进二级页时隐藏底部栏，避免遮挡
                barVisible = false
            }
        }

        composeView.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (barVisible) {
                    MobiGlasBottomBar(
                        items = items,
                        selectedIndex = selectedIndex,
                        onSelect = { idx ->
                            if (topLevel[idx] != navController.currentDestination?.id) {
                                navController.navigateTab(topLevel[idx])
                            }
                        },
                    )
                }
            }
        }

        // 顶部 status bar 留白给内容，底部 navigation bar 留白给底栏
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, 0)
            composeView.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getInt(KEY_CONSENT_VERSION, 0) >= LEGAL_VERSION) {
            autoCheckUpdate(prefs)
        } else {
            showConsentDialog(prefs)
        }
    }

    /** 首次启动（或协议版本升级后）弹出，同意前不发起任何网络请求 */
    private fun showConsentDialog(prefs: SharedPreferences) {
        val message = SpannableStringBuilder(
            "欢迎使用 SCMobiGlas。\n\n本应用不收集、不上传任何个人信息，" +
                "RSI 账号登录仅在您的设备本地完成。\n\n使用前请阅读并同意 ",
        )
        appendLegalLink(message, "《用户协议》", LegalDocs.AGREEMENT, "用户协议")
        message.append(" 与 ")
        appendLegalLink(message, "《隐私政策》", LegalDocs.PRIVACY, "隐私政策")
        message.append("。")

        val textView = TextView(this).apply {
            text = message
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(0xFFC9D1D9.toInt())
            textSize = 14f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("用户协议与隐私政策")
            .setView(textView)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                prefs.edit().putInt(KEY_CONSENT_VERSION, LEGAL_VERSION).apply()
                autoCheckUpdate(prefs)
            }
            .setNegativeButton("不同意") { _, _ -> finish() }
            .show()
    }

    private fun appendLegalLink(
        sb: SpannableStringBuilder,
        label: String,
        doc: String,
        title: String,
    ) {
        val start = sb.length
        sb.append(label)
        sb.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    LegalDocs.showDialog(this@MainActivity, doc, title)
                }
            },
            start,
            sb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** 启动后台静默检查更新，失败静默忽略；新版本且未被忽略时弹窗 */
    private fun autoCheckUpdate(prefs: SharedPreferences) {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { AppUpdateClient().fetchLatestRelease() }.getOrNull()
            } ?: return@launch
            val current = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
            val ignored = prefs.getString(KEY_IGNORED_VERSION, null)
            if (!UpdatePrompt.shouldPrompt(current, release.versionName, ignored)) return@launch
            if (isFinishing || isDestroyed) return@launch
            AppUpdateNotifier.showUpdateDialog(this@MainActivity, current, release) {
                prefs.edit().putString(KEY_IGNORED_VERSION, release.versionName).apply()
            }
        }
    }

    /** 底部栏切 Tab：保留各 Tab 返回栈状态，避免重复入栈 */
    private fun NavController.navigateTab(destId: Int) {
        navigate(
            destId,
            null,
            navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(graph.startDestinationId) { saveState = true }
            },
        )
    }
}
