package com.euedrc.bugsc.scm

data class AppAuthLoginRespVO(
    val userId: Long,
    val accessToken: String,
    val refreshToken: String,
    val expiresTime: Long,
    val openid: String? = null,
)

data class ScmResult(val success: Boolean, val msg: String, val code: Int) {
    companion object {
        fun ok(msg: String = "") = ScmResult(true, msg, 0)
    }
}

sealed class ScmRegisterOutcome {
    data class Success(val loggedIn: Boolean) : ScmRegisterOutcome()
    data class NeedEmailCode(val msg: String) : ScmRegisterOutcome()
    data class Failure(val code: Int, val msg: String) : ScmRegisterOutcome()
}

data class SignInSummary(
    val totalDay: Int,
    val continuousDay: Int,
    val todaySignIn: Boolean,
)
