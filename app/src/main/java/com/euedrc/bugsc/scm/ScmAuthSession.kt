package com.euedrc.bugsc.scm

import java.util.UUID

/** 键值存储抽象，便于单测注入内存实现，Android 侧用 EncryptedSharedPreferences。 */
interface ScmKvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

data class ScmSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresTime: Long,
    val userId: Long,
    val deviceId: String,
)

/**
 * SCM 登录态读写核心逻辑（与 Android 解耦，由 [ScmKvStore] 提供持久化）。
 * deviceId 为设备身份，首次生成后持久化，登出（clear）不清除。
 */
class ScmAuthSession(private val kv: ScmKvStore) {

    fun session(): ScmSession = ScmSession(
        accessToken = kv.getString(KEY_ACCESS) ?: "",
        refreshToken = kv.getString(KEY_REFRESH) ?: "",
        expiresTime = kv.getString(KEY_EXPIRES)?.toLongOrNull() ?: 0L,
        userId = kv.getString(KEY_USER_ID)?.toLongOrNull() ?: 0L,
        deviceId = kv.getString(KEY_DEVICE_ID) ?: "",
    )

    val isLoggedIn: Boolean get() = !kv.getString(KEY_ACCESS).isNullOrEmpty()

    fun save(login: AppAuthLoginRespVO) {
        kv.putString(KEY_ACCESS, login.accessToken)
        kv.putString(KEY_REFRESH, login.refreshToken)
        kv.putString(KEY_EXPIRES, login.expiresTime.toString())
        kv.putString(KEY_USER_ID, login.userId.toString())
    }

    /** 仅更新 token（refresh 后），保留 userId/deviceId。 */
    fun updateTokens(accessToken: String, refreshToken: String, expiresTime: Long) {
        kv.putString(KEY_ACCESS, accessToken)
        kv.putString(KEY_REFRESH, refreshToken)
        kv.putString(KEY_EXPIRES, expiresTime.toString())
    }

    fun clear() {
        kv.remove(KEY_ACCESS)
        kv.remove(KEY_REFRESH)
        kv.remove(KEY_EXPIRES)
        kv.remove(KEY_USER_ID)
    }

    /** 首启生成持久化 UUID，之后恒定返回同一值。 */
    fun ensureDeviceId(): String {
        kv.getString(KEY_DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        kv.putString(KEY_DEVICE_ID, id)
        return id
    }

    companion object {
        private const val KEY_ACCESS = "scmAccessToken"
        private const val KEY_REFRESH = "scmRefreshToken"
        private const val KEY_EXPIRES = "scmExpiresTime"
        private const val KEY_USER_ID = "scmUserId"
        private const val KEY_DEVICE_ID = "scmDeviceId"
    }
}
