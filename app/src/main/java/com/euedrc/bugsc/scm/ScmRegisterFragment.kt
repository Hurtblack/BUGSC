package com.euedrc.bugsc.scm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.euedrc.bugsc.R
import com.euedrc.bugsc.finishLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SCM 邮箱验证码注册：取图→点选→发码（60s 倒计时）→校验注册（成功带 token 自动登录）。 */
class ScmRegisterFragment : Fragment() {

    private lateinit var etEmail: EditText
    private lateinit var etEmailCode: EditText
    private lateinit var etPassword: EditText
    private lateinit var captchaView: ScmCaptchaView
    private lateinit var btnSendCode: Button
    private lateinit var btnRegister: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView

    private var pendingVerification: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scm_register, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etEmail = view.findViewById(R.id.et_email)
        etEmailCode = view.findViewById(R.id.et_email_code)
        etPassword = view.findViewById(R.id.et_password)
        captchaView = view.findViewById(R.id.captcha_view)
        btnSendCode = view.findViewById(R.id.btn_send_code)
        btnRegister = view.findViewById(R.id.btn_register)
        tvStatus = view.findViewById(R.id.tv_status)
        tvError = view.findViewById(R.id.tv_error)

        captchaView.onRequestRefresh = { loadCaptcha() }
        captchaView.onVerified = {
            pendingVerification = it
            tvStatus.text = "验证码已采集，可发送邮箱验证码"
        }
        loadCaptcha()

        btnSendCode.setOnClickListener { sendCode() }
        btnRegister.setOnClickListener { register() }
    }

    private fun loadCaptcha() {
        pendingVerification = null
        captchaView.showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val challenge = withContext(Dispatchers.IO) { runCatching { ScmClient.getCaptcha() }.getOrNull() }
            if (challenge != null) captchaView.setChallenge(challenge)
            else captchaView.showError("验证码加载失败，点击换一张重试")
        }
    }

    private fun sendCode() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty()) { showError("请输入邮箱"); return }
        val verification = pendingVerification
        if (verification.isNullOrEmpty()) { showError("请先完成图片验证码"); return }

        tvError.visibility = View.GONE
        btnSendCode.isEnabled = false
        tvStatus.text = "正在发送验证码…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScmClient.sendEmailCode(email, ScmClient.MAIL_TYPE_REGISTER, verification) }
                    .getOrElse { ScmResult(false, it.message ?: "网络错误", -1) }
            }
            if (result.success) {
                tvStatus.text = "验证码已发送，请查收邮箱"
                startCountdown()
            } else {
                btnSendCode.isEnabled = true
                showError(result.msg.ifBlank { "发送失败" })
            }
            loadCaptcha()
        }
    }

    private fun startCountdown() {
        viewLifecycleOwner.lifecycleScope.launch {
            for (s in 60 downTo 1) {
                btnSendCode.text = "重新发送(${s}s)"
                delay(1000)
            }
            btnSendCode.text = "发送邮箱验证码"
            btnSendCode.isEnabled = true
        }
    }

    private fun register() {
        val email = etEmail.text.toString().trim()
        val code = etEmailCode.text.toString().trim()
        val password = etPassword.text.toString()
        if (email.isEmpty() || code.isEmpty() || password.isEmpty()) {
            showError("邮箱、验证码、密码不能为空"); return
        }
        tvError.visibility = View.GONE
        tvStatus.text = "正在注册…"
        btnRegister.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ScmClient.verifyAndRegister(email, password, code, pendingVerification) }
                    .getOrElse { ScmRegisterOutcome.Failure(-1, it.message ?: "网络错误") }
            }
            btnRegister.isEnabled = true
            when (outcome) {
                is ScmRegisterOutcome.Success ->
                    if (outcome.loggedIn) finishLogin() else findNavController().popBackStack()
                is ScmRegisterOutcome.NeedEmailCode -> showError(outcome.msg.ifBlank { "请输入邮箱验证码" })
                is ScmRegisterOutcome.Failure -> showError(outcome.msg.ifBlank { "注册失败" })
            }
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
