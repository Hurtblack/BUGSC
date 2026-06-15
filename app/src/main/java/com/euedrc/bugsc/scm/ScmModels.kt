package com.euedrc.bugsc.scm

import org.json.JSONObject

/**
 * SCM (flowcld) `app-api` 统一响应包：`{ code, data, msg }`。code == 0 为成功。
 */
class ScmResponse(
    val code: Int,
    val msg: String,
    val data: JSONObject?,
) {
    val isSuccess: Boolean get() = code == 0

    companion object {
        fun parse(body: String): ScmResponse = from(JSONObject(body))

        fun from(json: JSONObject): ScmResponse {
            val data = if (json.isNull("data")) null else json.optJSONObject("data")
            return ScmResponse(
                code = json.optInt("code", -1),
                msg = json.optString("msg", ""),
                data = data,
            )
        }
    }
}

/** 登录返回 `AppAuthLoginRespVO`。 */
data class AppAuthLoginRespVO(
    val userId: Long,
    val accessToken: String,
    val refreshToken: String,
    val expiresTime: Long,
    val openid: String? = null,
) {
    companion object {
        fun parse(data: JSONObject): AppAuthLoginRespVO = AppAuthLoginRespVO(
            userId = data.optLong("userId"),
            accessToken = data.optString("accessToken"),
            refreshToken = data.optString("refreshToken"),
            expiresTime = data.optLong("expiresTime"),
            openid = if (data.isNull("openid")) null else data.optString("openid").ifEmpty { null },
        )
    }
}

sealed class ScmLoginOutcome {
    data class Success(val login: AppAuthLoginRespVO) : ScmLoginOutcome()
    /** 新设备未受信任：需先发邮箱验证码再带 emailVerCode 重发登录。 */
    data class NeedEmailCode(val msg: String) : ScmLoginOutcome()
    data class Failure(val code: Int, val msg: String) : ScmLoginOutcome()
}

object ScmLogin {
    /**
     * 新设备未受信任、要求邮箱二次验证的业务码（实测官网：catch code===1004023002 即弹邮箱码输入）。
     * 后端在该次登录失败时已自动发送邮箱验证码。
     */
    const val CODE_EMAIL_CODE_REQUIRED = 1004023002

    fun interpret(resp: ScmResponse): ScmLoginOutcome {
        if (resp.isSuccess && resp.data != null) {
            return ScmLoginOutcome.Success(AppAuthLoginRespVO.parse(resp.data))
        }
        if (resp.code == CODE_EMAIL_CODE_REQUIRED) {
            return ScmLoginOutcome.NeedEmailCode(resp.msg)
        }
        return ScmLoginOutcome.Failure(resp.code, resp.msg)
    }
}

/** `GET /member/sign-in/record/get-summary` 返回个人签到统计。 */
data class SignInSummary(
    val totalDay: Int,
    val continuousDay: Int,
    val todaySignIn: Boolean,
) {
    companion object {
        fun parse(resp: ScmResponse): SignInSummary? {
            val data = resp.data ?: return null
            if (!resp.isSuccess) return null
            return SignInSummary(
                totalDay = data.optInt("totalDay", 0),
                continuousDay = data.optInt("continuousDay", 0),
                todaySignIn = data.optBoolean("todaySignIn", false),
            )
        }
    }
}

/** `GET /member/user/get` 返回 `AppMemberUserInfoRespVO`（自己，含私有字段）。 */
data class AppMemberUserInfoRespVO(
    val id: Long,
    val nickname: String,
    val avatar: String,
    val email: String,
    val mark: String,
    val language: Int,
    val verifyKeyStatus: Int,
    val rsiAccurate: Int?,
    val sponsorLevel: Int,
    val sellOrderCount: Int,
    val buyOrderCount: Int,
    val userMobile: String,
    val createTime: Long,
    val organization: String,
    val signInStatus: Int,
    val reputationPoint: Int,
    val orderLimit: Int,
    val groups: List<String>,
) {
    fun toCacheJson(): String = JSONObject()
        .put("id", id)
        .put("nickname", nickname)
        .put("avatar", avatar)
        .put("email", email)
        .put("mark", mark)
        .put("language", language)
        .put("verifyKeyStatus", verifyKeyStatus)
        .put("rsiAccurate", rsiAccurate)
        .put("sponsorLevel", sponsorLevel)
        .put("sellOrderCount", sellOrderCount)
        .put("buyOrderCount", buyOrderCount)
        .put("userMobile", userMobile)
        .put("createTime", createTime)
        .put("organization", organization)
        .put("signInStatus", signInStatus)
        .put("reputationPoint", reputationPoint)
        .put("orderLimit", orderLimit)
        .put("groups", org.json.JSONArray(groups))
        .toString()

    companion object {
        // org.json 的 optString 遇到 JSON null 会返回字符串 "null"，这里统一当空串。
        private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

        fun parse(data: JSONObject): AppMemberUserInfoRespVO = AppMemberUserInfoRespVO(
            id = data.optLong("id"),
            nickname = data.str("nickname"),
            avatar = data.str("avatar"),
            email = data.str("email"),
            mark = data.str("mark"),
            language = data.optInt("language", 0),
            verifyKeyStatus = data.optInt("verifyKeyStatus", 0),
            rsiAccurate = if (data.isNull("rsiAccurate")) null else data.optInt("rsiAccurate"),
            sponsorLevel = data.optInt("sponsorLevel", 0),
            sellOrderCount = data.optInt("sellOrderCount", 0),
            buyOrderCount = data.optInt("buyOrderCount", 0),
            userMobile = data.str("userMobile"),
            createTime = data.optLong("createTime"),
            // organization 是对象 {sid,name,rank,...}，只取舰队名。
            organization = data.optJSONObject("organization")?.let { if (it.isNull("name")) "" else it.optString("name") }.orEmpty(),
            signInStatus = data.optInt("signInStatus", 0),
            reputationPoint = data.optJSONObject("achievement")?.optInt("point", 0) ?: 0,
            orderLimit = data.optJSONObject("achievement")?.optInt("orderLimit", 0) ?: 0,
            groups = data.optJSONObject("achievement")?.optJSONArray("groups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { g ->
                        (if (g.isNull("title")) "" else g.optString("title"))
                            .ifBlank { if (g.isNull("name")) "" else g.optString("name") }
                            .takeIf { it.isNotBlank() }
                    }
                }
            } ?: emptyList(),
        )

        fun parseCache(body: String): AppMemberUserInfoRespVO? = runCatching {
            val data = JSONObject(body)
            val groups = data.optJSONArray("groups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
            } ?: emptyList()
            AppMemberUserInfoRespVO(
                id = data.optLong("id"),
                nickname = data.str("nickname"),
                avatar = data.str("avatar"),
                email = data.str("email"),
                mark = data.str("mark"),
                language = data.optInt("language", 0),
                verifyKeyStatus = data.optInt("verifyKeyStatus", 0),
                rsiAccurate = if (data.isNull("rsiAccurate")) null else data.optInt("rsiAccurate"),
                sponsorLevel = data.optInt("sponsorLevel", 0),
                sellOrderCount = data.optInt("sellOrderCount", 0),
                buyOrderCount = data.optInt("buyOrderCount", 0),
                userMobile = data.str("userMobile"),
                createTime = data.optLong("createTime"),
                organization = data.str("organization"),
                signInStatus = data.optInt("signInStatus", 0),
                reputationPoint = data.optInt("reputationPoint", 0),
                orderLimit = data.optInt("orderLimit", 0),
                groups = groups,
            )
        }.getOrNull()
    }
}
