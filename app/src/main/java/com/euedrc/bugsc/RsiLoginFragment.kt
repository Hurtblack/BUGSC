package com.euedrc.bugsc

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.euedrc.bugsc.analytics.AnalyticsTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSI 独立登录页（账密 + 图片验证码 + 二步验证），从 InventoryFragment 抽出。
 * 成功后把 [RsiSession] 存入 inventory prefs（与 RsiCookieStore 一致），并按 returnDestId 返回。
 */
class RsiLoginFragment : Fragment() {

    private val prefs by lazy { requireContext().getSharedPreferences("inventory", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var client = RsiInventoryClient()
    private var pendingSecondStep = false
    private var pendingCaptcha = false

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etCaptcha: EditText
    private lateinit var etCode: EditText
    private lateinit var tvCaptchaLabel: TextView
    private lateinit var tvCodeLabel: TextView
    private lateinit var ivCaptcha: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var btnLogin: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_rsi_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        etCaptcha = view.findViewById(R.id.et_captcha)
        etCode = view.findViewById(R.id.et_code)
        tvCaptchaLabel = view.findViewById(R.id.tv_captcha_label)
        tvCodeLabel = view.findViewById(R.id.tv_code_label)
        ivCaptcha = view.findViewById(R.id.iv_captcha)
        tvStatus = view.findViewById(R.id.tv_status)
        tvError = view.findViewById(R.id.tv_error)
        btnLogin = view.findViewById(R.id.btn_login)

        etUsername.setText(prefs.getString(KEY_USERNAME, "") ?: "")
        client = RsiInventoryClient(loadSession())

        btnLogin.setOnClickListener {
            track(
                when {
                    pendingSecondStep -> "submit_second_step"
                    pendingCaptcha -> "submit_captcha"
                    else -> "login"
                },
            )
            when {
                pendingSecondStep -> submitSecondStepCode()
                pendingCaptcha -> submitCaptcha()
                else -> login()
            }
        }
        ivCaptcha.setOnClickListener {
            track("refresh_captcha")
            loadCaptchaImage()
        }
    }

    private fun login() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError("用户名和密码不能为空"); return
        }
        tvStatus.text = "正在登录 RSI..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.login(username, password) }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "登录失败"; showError(e.message ?: "登录失败")
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun submitCaptcha() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val captcha = etCaptcha.text.toString().trim()
        if (captcha.isEmpty()) { showError("请输入图片验证码"); return }
        if (username.isEmpty() || password.isEmpty()) { showError("用户名和密码不能为空"); return }
        tvStatus.text = "正在提交图片验证码..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.login(username, password, captcha) }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "图片验证码提交失败"; showError(e.message ?: "图片验证码提交失败"); loadCaptchaImage()
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun submitSecondStepCode() {
        val username = etUsername.text.toString().trim()
        val code = etCode.text.toString().trim()
        if (code.isEmpty()) { showError("请输入二步验证码"); return }
        tvStatus.text = "正在提交二步验证码..."
        tvError.visibility = View.GONE
        btnLogin.isEnabled = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.submitCode(code) }
                handleLoginResult(result, username)
            } catch (e: Exception) {
                tvStatus.text = "验证码提交失败"; showError(e.message ?: "验证码提交失败")
            } finally {
                btnLogin.isEnabled = true
            }
        }
    }

    private fun handleLoginResult(result: LoginResult, username: String) {
        if (result.success) {
            pendingSecondStep = false
            pendingCaptcha = false
            setCaptchaInputVisible(false)
            setCodeInputVisible(false)
            saveSession(username, result.session)
            tvStatus.text = "已登录：$username"
            finishLogin()
            return
        }
        if (result.needCode) {
            pendingSecondStep = true
            pendingCaptcha = false
            setCaptchaInputVisible(false)
            setCodeInputVisible(true)
            btnLogin.text = "提交验证码"
            tvStatus.text = result.message
            tvError.visibility = View.GONE
            return
        }
        if (result.needCaptcha) {
            pendingCaptcha = true
            pendingSecondStep = false
            setCodeInputVisible(false)
            setCaptchaInputVisible(true)
            tvStatus.text = "请输入 RSI 图片验证码"
            tvError.visibility = View.GONE
            loadCaptchaImage()
            return
        }
        tvStatus.text = "登录失败"
        showError(result.message)
    }

    private fun loadCaptchaImage() {
        tvStatus.text = "正在加载 RSI 图片验证码..."
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { client.fetchCaptchaImage() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("验证码图片解析失败")
                ivCaptcha.setImageBitmap(bitmap)
                tvStatus.text = "请输入图片中的验证码"
            } catch (e: Exception) {
                tvStatus.text = "验证码加载失败"; showError(e.message ?: "验证码加载失败")
            }
        }
    }

    private fun setCodeInputVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        tvCodeLabel.visibility = visibility
        etCode.visibility = visibility
        btnLogin.text = if (visible) "提交验证码" else "登录"
    }

    private fun setCaptchaInputVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        tvCaptchaLabel.visibility = visibility
        ivCaptcha.visibility = visibility
        etCaptcha.visibility = visibility
        btnLogin.text = if (visible) "提交图片验证码" else "登录"
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun loadSession(): RsiSession = RsiSession(
        token = prefs.getString(KEY_TOKEN, "") ?: "",
        device = prefs.getString(KEY_DEVICE, "") ?: "",
        accountAuth = prefs.getString(KEY_AUTH, "") ?: "",
        upgradeContext = prefs.getString(KEY_UPGRADE_CONTEXT, "") ?: "",
    )

    private fun saveSession(username: String, session: RsiSession) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_DEVICE, session.device)
            .putString(KEY_AUTH, session.accountAuth)
            .putString(KEY_UPGRADE_CONTEXT, session.upgradeContext)
            .apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("rsi_login", feature)
    }

    companion object {
        private const val KEY_USERNAME = "inventoryUsername"
        private const val KEY_TOKEN = "inventoryRsiToken"
        private const val KEY_DEVICE = "inventoryRsiDevice"
        private const val KEY_AUTH = "inventoryRsiAccountAuth"
        private const val KEY_UPGRADE_CONTEXT = "inventoryRsiUpgradeContext"
    }
}
