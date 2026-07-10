package com.euedrc.bugsc.scm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SCM 登录态单例（对标 [com.euedrc.bugsc.RsiCookieStore]）。
 * 用 EncryptedSharedPreferences 持久化 token / deviceId，核心读写逻辑委托给可单测的 [ScmAuthSession]。
 * [loginState] 在登录/登出时变更，Profile 与功能页可观察自动刷新。
 */
object ScmAuthStore {

    private const val PREFS = "scm_auth"

    private lateinit var authSession: ScmAuthSession

    private val _loginState = MutableStateFlow(false)
    val loginState: StateFlow<Boolean> get() = _loginState

    @Synchronized
    fun init(context: Context) {
        if (::authSession.isInitialized) return
        val kv = SharedPrefsKvStore(buildPrefs(context.applicationContext))
        authSession = ScmAuthSession(kv)
        authSession.ensureDeviceId()
        _loginState.value = authSession.isLoggedIn
    }

    fun session(): ScmSession = authSession.session()
    val isLoggedIn: Boolean get() = authSession.isLoggedIn
    fun deviceId(): String = authSession.ensureDeviceId()

    fun save(login: AppAuthLoginRespVO) {
        authSession.save(login)
        _loginState.value = true
    }

    fun clear() {
        authSession.clear()
        _loginState.value = false
        com.euedrc.bugsc.chat.ChatUnreadStore.clear()
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // 个别设备 Keystore 异常时退回普通 prefs，保证可用性。
            context.getSharedPreferences("${PREFS}_plain", Context.MODE_PRIVATE)
        }
    }

    private class SharedPrefsKvStore(private val prefs: SharedPreferences) : ScmKvStore {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }
}
