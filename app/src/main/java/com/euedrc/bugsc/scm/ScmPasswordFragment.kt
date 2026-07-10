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
import com.euedrc.bugsc.analytics.AnalyticsTracker
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.data.AuthDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SCM 密码：mode=reset 忘密重置（邮箱+验证码+邮箱码+新密码）；mode=change 已登录改密（旧+新密码）。 */
class ScmPasswordFragment : Fragment() {
    private val auth get() = AppServices.auth

    private val mode: String get() = arguments?.getString("mode") ?: MODE_RESET

    private lateinit var tvTitle: TextView
    private lateinit var etOldPassword: EditText
    private lateinit var containerReset: View
    private lateinit var etEmail: EditText
    private lateinit var etEmailCode: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var captchaView: ScmCaptchaView
    private lateinit var btnSendCode: Button
    private lateinit var btnSubmit: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView

    private var pendingVerification: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_scm_password, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvTitle = view.findViewById(R.id.tv_title)
        etOldPassword = view.findViewById(R.id.et_old_password)
        containerReset = view.findViewById(R.id.container_reset)
        etEmail = view.findViewById(R.id.et_email)
        etEmailCode = view.findViewById(R.id.et_email_code)
        etNewPassword = view.findViewById(R.id.et_new_password)
        captchaView = view.findViewById(R.id.captcha_view)
        btnSendCode = view.findViewById(R.id.btn_send_code)
        btnSubmit = view.findViewById(R.id.btn_submit)
        tvStatus = view.findViewById(R.id.tv_status)
        tvError = view.findViewById(R.id.tv_error)

        if (mode == MODE_CHANGE) {
            tvTitle.text = "修改密码"
            etOldPassword.visibility = View.VISIBLE
            containerReset.visibility = View.GONE
        } else {
            tvTitle.text = "重置密码"
            etOldPassword.visibility = View.GONE
            containerReset.visibility = View.VISIBLE
            captchaView.onRequestRefresh = {
                track("refresh_captcha")
                loadCaptcha()
            }
            captchaView.onVerified = {
                pendingVerification = it
                tvStatus.text = "验证码已采集，可发送邮箱验证码"
            }
            loadCaptcha()
            btnSendCode.setOnClickListener {
                track("send_email_code")
                sendCode()
            }
        }

        btnSubmit.setOnClickListener {
            track(if (mode == MODE_CHANGE) "change_password" else "reset_password")
            submit()
        }
    }

    private fun loadCaptcha() {
        pendingVerification = null
        captchaView.showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val challenge = withContext(Dispatchers.IO) { runCatching { auth.getCaptcha() }.getOrNull() }
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
                runCatching {
                    auth.sendEmailCode(email, AuthDataSource.MAIL_TYPE_RESET, verification)
                }.getOrElse {
                    ScmResult(false, it.message ?: "网络错误", -1)
                }
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

    private fun submit() {
        val newPassword = etNewPassword.text.toString()
        if (newPassword.isEmpty()) { showError("请输入新密码"); return }

        if (mode == MODE_CHANGE) {
            val oldPassword = etOldPassword.text.toString()
            if (oldPassword.isEmpty()) { showError("请输入旧密码"); return }
            runRequest("正在修改密码…") { auth.changePassword(oldPassword, newPassword) }
        } else {
            val email = etEmail.text.toString().trim()
            val code = etEmailCode.text.toString().trim()
            if (email.isEmpty() || code.isEmpty()) { showError("邮箱和邮箱验证码不能为空"); return }
            runRequest("正在重置密码…") { auth.resetPassword(email, code, newPassword) }
        }
    }

    private fun runRequest(progress: String, block: suspend () -> ScmResult) {
        tvError.visibility = View.GONE
        tvStatus.text = progress
        btnSubmit.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { ScmResult(false, it.message ?: "网络错误", -1) }
            }
            btnSubmit.isEnabled = true
            if (result.success) {
                tvStatus.text = "操作成功"
                findNavController().popBackStack()
            } else {
                showError(result.msg.ifBlank { "操作失败" })
            }
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun track(feature: String) {
        AnalyticsTracker.get(requireContext()).trackFeatureClick("scm_password", feature)
    }

    companion object {
        const val MODE_RESET = "reset"
        const val MODE_CHANGE = "change"
    }
}
