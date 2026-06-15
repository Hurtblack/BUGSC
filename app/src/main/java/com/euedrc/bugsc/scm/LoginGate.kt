package com.euedrc.bugsc.scm

/**
 * 登录门禁决策（与 Android 解耦）：已登录直接执行动作，未登录则导航到登录页并带返回目标。
 * Fragment 扩展 requireRsiLogin / requireScmLogin 基于此决策做实际跳转。
 */
object LoginGate {

    sealed class Decision {
        object Execute : Decision()
        data class Navigate(val returnDestId: Int, val hasReturnArgs: Boolean = false) : Decision()
    }

    fun decide(isLoggedIn: Boolean, returnDestId: Int, hasReturnArgs: Boolean = false): Decision =
        if (isLoggedIn) Decision.Execute else Decision.Navigate(returnDestId, hasReturnArgs)
}
