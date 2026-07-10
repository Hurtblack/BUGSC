package com.euedrc.bugsc.data

import com.euedrc.bugsc.scm.ScmResult
import com.euedrc.bugsc.scm.ScmRegisterOutcome
import com.euedrc.bugsc.scm.SignInSummary

data class AppUserProfile(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
)

data class AppCaptchaPrompt(
    val originalImageBase64: String,
    val wordList: List<String>,
    val verificationToken: String = "",
)

sealed class AppLoginResult {
    data class Success(val userId: Long) : AppLoginResult()
    data class NeedEmailCode(val message: String) : AppLoginResult()
    data class Failure(val message: String) : AppLoginResult()
}

interface AuthDataSource {
    companion object {
        const val MAIL_TYPE_REGISTER = 0
        const val MAIL_TYPE_RESET = 1
    }

    fun isLoggedIn(): Boolean
    fun currentUserId(): Long
    suspend fun login(
        email: String,
        password: String,
        captchaVerification: String,
        emailCode: String? = null,
    ): AppLoginResult
    suspend fun getCaptcha(): AppCaptchaPrompt? = null
    suspend fun sendEmailCode(email: String, mailType: Int, captchaVerification: String): ScmResult =
        ScmResult(false, "当前构建未启用 SCM 邮箱验证码", -1)
    suspend fun register(
        email: String,
        password: String,
        emailCode: String,
        captchaVerification: String?,
    ): ScmRegisterOutcome = ScmRegisterOutcome.Failure(-1, "当前构建未启用 SCM 注册")
    suspend fun changePassword(oldPassword: String, newPassword: String): ScmResult =
        ScmResult(false, "当前构建未启用 SCM 改密", -1)
    suspend fun resetPassword(email: String, emailCode: String, newPassword: String): ScmResult =
        ScmResult(false, "当前构建未启用 SCM 重置密码", -1)
    suspend fun signInSummary(): SignInSummary? = null
    suspend fun signIn(): ScmResult = ScmResult(false, "当前构建未启用 SCM 签到", -1)
    suspend fun logout()
    suspend fun getUserInfo(): AppUserProfile?
}
