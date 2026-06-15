package com.euedrc.bugsc.scm

import org.json.JSONObject

/** SCM 业务结果（非登录类接口的通用返回）。 */
data class ScmResult(val success: Boolean, val msg: String, val code: Int) {
    companion object {
        fun of(resp: ScmResponse) = ScmResult(resp.isSuccess, resp.msg, resp.code)
    }
}

sealed class ScmRegisterOutcome {
    /** loggedIn=true 表示注册即拿到 token 自动登录；false 表示注册成功但需去登录页。 */
    data class Success(val loggedIn: Boolean) : ScmRegisterOutcome()
    data class NeedEmailCode(val msg: String) : ScmRegisterOutcome()
    data class Failure(val code: Int, val msg: String) : ScmRegisterOutcome()
}

/**
 * SCM 账号体系业务门面：登录 / 注册 / 发邮箱码 / 改密 / 个人信息 / 登出。
 * 所有方法均为阻塞 HTTP 调用，调用方须在 IO 线程执行。
 * 解析逻辑复用已单测的 [ScmResponse] / [ScmLogin] / [AppMemberUserInfoRespVO]。
 */
object ScmClient {

    // emailVerCode 的 type（scene），来源 reme.md：0=注册 1=忘记密码 2=修改邮箱。
    const val MAIL_TYPE_REGISTER = 0
    const val MAIL_TYPE_RESET = 1


    private fun api() = ScmAuthStore.api()
    private fun deviceId() = ScmAuthStore.deviceId()

    fun getCaptcha(): CaptchaChallenge? {
        val body = JSONObject().put("captchaType", "clickWord").toString()
        val resp = api().request("POST", "/system/captcha/get", body)
        if (resp.code !in 200..299) return null
        return CaptchaChallenge.parse(resp.body)
    }

    /**
     * AJ-Captcha 点选二次校验：把本次点选标记为通过，业务登录/发码才认这张验证码。
     * body `{captchaType, token, pointJson:AES(pointJson)}`，repCode "0000" 通过。
     */
    fun checkCaptcha(token: String, encryptedPointJson: String): Boolean {
        val body = JSONObject().apply {
            put("captchaType", "clickWord")
            put("token", token)
            put("pointJson", encryptedPointJson)
        }.toString()
        val resp = api().request("POST", "/system/captcha/check", body)
        if (resp.code !in 200..299) return false
        return runCatching { JSONObject(resp.body).optString("repCode") == "0000" }.getOrDefault(false)
    }

    /** 兜底查询该邮箱登录是否需要人机验证；失败时按"需要"处理更稳。 */
    fun loginCaptchaRequired(email: String): Boolean {
        val resp = api().request("GET", "/member/auth/login-captcha-required?email=$email")
        if (resp.code !in 200..299) return true
        val parsed = ScmResponse.parse(resp.body)
        // data 可能是 true/false 布尔或 {required:true}
        return parsed.data?.optBoolean("required", true)
            ?: runCatching { resp.body.contains("true") }.getOrDefault(true)
    }

    fun emailLogin(
        email: String,
        password: String,
        captchaVerification: String,
        emailVerCode: String? = null,
    ): ScmLoginOutcome {
        val body = JSONObject().apply {
            put("email", email)
            put("password", ScmCrypto.rsaEncryptPassword(password))
            put("captchaVerification", captchaVerification)
            put("deviceId", deviceId())
            if (!emailVerCode.isNullOrBlank()) put("emailVerCode", emailVerCode)
        }.toString()
        val resp = api().request("POST", "/member/auth/emailLogin", body)
        val outcome = ScmLogin.interpret(ScmResponse.parse(resp.body))
        if (outcome is ScmLoginOutcome.Success) ScmAuthStore.save(outcome.login)
        return outcome
    }

    fun sendEmailCode(email: String, type: Int, captchaVerification: String): ScmResult {
        val body = JSONObject().apply {
            put("email", email)
            put("type", type)
            put("captchaVerification", captchaVerification)
        }.toString()
        return ScmResult.of(ScmResponse.parse(api().request("POST", "/member/auth/emailVerCode", body).body))
    }

    fun verifyAndRegister(
        email: String,
        password: String,
        code: String,
        captchaVerification: String? = null,
    ): ScmRegisterOutcome {
        val body = JSONObject().apply {
            put("email", email)
            put("password", ScmCrypto.rsaEncryptPassword(password))
            put("code", code)
            put("deviceId", deviceId())
            if (!captchaVerification.isNullOrBlank()) put("captchaVerification", captchaVerification)
        }.toString()
        val resp = ScmResponse.parse(api().request("POST", "/member/auth/verifyAndRegister", body).body)
        if (!resp.isSuccess) return ScmRegisterOutcome.Failure(resp.code, resp.msg)
        val data = resp.data
        if (data != null && data.optString("accessToken").isNotEmpty()) {
            ScmAuthStore.save(AppAuthLoginRespVO.parse(data))
            return ScmRegisterOutcome.Success(loggedIn = true)
        }
        return ScmRegisterOutcome.Success(loggedIn = false)
    }

    fun resetPassword(email: String, code: String, newPassword: String): ScmResult {
        val body = JSONObject().apply {
            put("email", email)
            put("code", code)
            put("password", ScmCrypto.rsaEncryptPassword(newPassword))
        }.toString()
        return ScmResult.of(ScmResponse.parse(api().request("POST", "/member/auth/resetPassword", body).body))
    }

    fun changePassword(oldPassword: String, newPassword: String): ScmResult {
        val body = JSONObject().apply {
            put("oldPassword", ScmCrypto.rsaEncryptPassword(oldPassword))
            put("newPassword", ScmCrypto.rsaEncryptPassword(newPassword))
        }.toString()
        return ScmResult.of(ScmResponse.parse(api().request("POST", "/member/auth/changePassword", body).body))
    }

    /** 个人签到统计（连续/累计/今日是否已签）。 */
    fun signInSummary(): SignInSummary? {
        val resp = api().request("GET", "/member/sign-in/record/get-summary")
        if (resp.code !in 200..299) return null
        return SignInSummary.parse(ScmResponse.parse(resp.body))
    }

    /** 签到。 */
    fun signIn(): ScmResult =
        ScmResult.of(ScmResponse.parse(api().request("POST", "/member/sign-in/record/create", "{}").body))

    fun getUserInfo(): AppMemberUserInfoRespVO? {
        val resp = api().request("GET", "/member/user/get")
        if (resp.code !in 200..299) return null
        val parsed = ScmResponse.parse(resp.body)
        val data = parsed.data ?: return null
        if (!parsed.isSuccess) return null
        return AppMemberUserInfoRespVO.parse(data)
    }

    /** 登出：调服务端注销后本地清态（即使服务端失败也清本地）。 */
    fun logout(): ScmResult {
        val result = runCatching {
            ScmResult.of(ScmResponse.parse(api().request("POST", "/member/auth/logout").body))
        }.getOrDefault(ScmResult(false, "", -1))
        ScmAuthStore.clear()
        return result
    }
}
