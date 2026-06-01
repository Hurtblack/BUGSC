package com.euedrc.bugsc

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

object RsiCookieStore {
    const val PREFS = "inventory"
    const val KEY_TOKEN = "inventoryRsiToken"
    const val KEY_DEVICE = "inventoryRsiDevice"
    const val KEY_AUTH = "inventoryRsiAccountAuth"
    const val KEY_UPGRADE_CONTEXT = "inventoryRsiUpgradeContext"

    const val BASE_URL = "https://robertsspaceindustries.com/"
    const val ACCOUNT_RESET_URL = "${BASE_URL}en/account/reset"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val COOKIE_DOMAIN = ".robertsspaceindustries.com"
    private const val COOKIE_ORIGIN = "https://robertsspaceindustries.com"
    private const val ISSUE_COOKIE_ORIGIN = "https://issue-council.robertsspaceindustries.com"
    private const val COOKIE_CONSENT =
        "{stamp:%27OxTvKGMly/MLoYR3VVQb40QHQbh68uSc2ORKIfKGhLQyPGB71fbjEA==%27%2Cnecessary:true%2Cpreferences:false%2Cstatistics:false%2Cmarketing:true%2Cmethod:%27explicit%27%2Cver:1%2Cutc:1698762520261%2Cregion:%27gb%27}"

    fun loadSession(context: Context): RsiSession {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RsiSession(
            token = prefs.getString(KEY_TOKEN, "") ?: "",
            device = prefs.getString(KEY_DEVICE, "") ?: "",
            accountAuth = prefs.getString(KEY_AUTH, "") ?: "",
            upgradeContext = prefs.getString(KEY_UPGRADE_CONTEXT, "") ?: ""
        )
    }

    fun injectIntoWebView(context: Context, webView: WebView): RsiSession {
        val session = loadSession(context)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        setCookie(cookieManager, "CookieConsent", COOKIE_CONSENT)
        if (session.device.isNotEmpty()) {
            setCookie(cookieManager, "_rsi_device", session.device)
        }
        if (session.token.isNotEmpty()) {
            setCookie(cookieManager, "Rsi-Token", session.token)
        }
        if (session.accountAuth.isNotEmpty()) {
            setCookie(cookieManager, "Rsi-Account-Auth", session.accountAuth)
        }
        if (session.upgradeContext.isNotEmpty()) {
            setCookie(cookieManager, "Rsi-ShipUpgrades-Context", session.upgradeContext)
        }
        cookieManager.flush()
        return session
    }

    fun cookieHeader(session: RsiSession): String {
        return listOf(
            "CookieConsent=$COOKIE_CONSENT",
            "_rsi_device=${session.device}",
            "Rsi-Token=${session.token}",
            "Rsi-Account-Auth=${session.accountAuth}",
            "Rsi-ShipUpgrades-Context=${session.upgradeContext}"
        ).joinToString(";")
    }

    private fun cookieValue(name: String, value: String): String {
        return "$name=$value; path=/; domain=$COOKIE_DOMAIN; secure"
    }

    private fun setCookie(cookieManager: CookieManager, name: String, value: String) {
        val cookie = cookieValue(name, value)
        cookieManager.setCookie(COOKIE_ORIGIN, cookie)
        cookieManager.setCookie(ISSUE_COOKIE_ORIGIN, cookie)
    }
}
