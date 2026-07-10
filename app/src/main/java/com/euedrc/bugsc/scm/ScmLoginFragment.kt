package com.euedrc.bugsc.scm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.ARG_RETURN_ARGS
import com.euedrc.bugsc.ARG_RETURN_DEST
import com.euedrc.bugsc.R
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppLoginResult
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.finishLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SCM 邮箱密码登录 + 文字点选验证码，含"去注册 / 忘密"入口。 */
class ScmLoginFragment : Fragment() {
    private val auth get() = AppServices.auth

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etEmailCode: EditText
    private lateinit var tvEmailCodeLabel: TextView
    private lateinit var captchaView: ScmCaptchaView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var btnLogin: Button

    private var pendingVerification: String? = null
    private var newDeviceMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scm_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etEmail = view.findViewById(R.id.et_email)
        etPassword = view.findViewById(R.id.et_password)
        etEmailCode = view.findViewById(R.id.et_email_code)
        tvEmailCodeLabel = view.findViewById(R.id.tv_email_code_label)
        captchaView = view.findViewById(R.id.captcha_view)
        tvStatus = view.findViewById(R.id.tv_status)
        tvError = view.findViewById(R.id.tv_error)
        btnLogin = view.findViewById(R.id.btn_login)

        captchaView.onRequestRefresh = {
            track("refresh_captcha")
            loadCaptcha()
        }
        captchaView.onVerified = {
            pendingVerification = it
            tvStatus.text = "验证码已采集，点击登录"
        }
        loadCaptcha()

        btnLogin.setOnClickListener {
            track(if (newDeviceMode) "login_new_device" else "login")
            attemptLogin()
        }
        view.findViewById<View>(R.id.btn_register).setOnClickListener {
            track("open_register")
            findNavController().navigate(
                R.id.action_ScmLogin_to_ScmRegister,
                bundleOf(ARG_RETURN_DEST to returnDestId()).apply {
                    arguments?.getBundle(ARG_RETURN_ARGS)?.let { putBundle(ARG_RETURN_ARGS, it) }
                },
            )
        }
        view.findViewById<View>(R.id.btn_forgot).setOnClickListener {
            track("open_reset_password")
            findNavController().navigate(
                R.id.action_ScmLogin_to_ScmPassword,
                bundleOf("mode" to "reset"),
            )
        }
    }

    private fun returnDestId(): Int = arguments?.getInt(ARG_RETURN_DEST, 0) ?: 0

    private fun loadCaptcha() {
        pendingVerification = null
        captchaView.showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val challenge = withContext(Dispatchers.IO) { runCatching { auth.getCaptcha() }.getOrNull() }
            if (challenge != null) captchaView.setChallenge(challenge)
            else captchaView.showError("验证码加载失败，点击换一张重试")
        }
    }

    private fun attemptLogin() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            showError("请输入邮箱和密码"); return
        }
        val verification = pendingVerification
        if (verification.isNullOrEmpty()) {
            showError("请先完成图片验证码"); return
        }
        val emailCode = if (newDeviceMode) etEmailCode.text.toString().trim().ifEmpty { null } else null

        tvError.visibility = View.GONE
        tvStatus.text = "正在登录…"
        btnLogin.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    auth.login(email, password, verification, emailCode)
                }.getOrElse {
                    AppLoginResult.Failure(it.message ?: "网络错误")
                }
            }
            btnLogin.isEnabled = true
            when (outcome) {
                is AppLoginResult.Success -> finishLogin()
                is AppLoginResult.NeedEmailCode -> enterNewDeviceMode(outcome.message)
                is AppLoginResult.Failure -> {
                    showError(outcome.message.ifBlank { "登录失败" })
                    loadCaptcha()
                }
            }
        }
    }

    private fun enterNewDeviceMode(msg: String) {
        newDeviceMode = true
        tvEmailCodeLabel.visibility = View.VISIBLE
        etEmailCode.visibility = View.VISIBLE
        // 后端在本次登录尝试时通常已向邮箱发送验证码；用户重做图片验证码 + 填邮箱码后再次登录。
        tvStatus.text = msg.ifBlank { "新设备：验证码已发至邮箱，请完成图片验证码并输入邮箱验证码后重新登录" }
        loadCaptcha()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("scm_login", feature)
    }
}
