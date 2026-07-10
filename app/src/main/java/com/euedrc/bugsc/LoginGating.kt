package com.euedrc.bugsc

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import android.os.Bundle
import com.euedrc.bugsc.data.AppServices
import com.euedrc.bugsc.scm.LoginGate

/** 登录页接收的导航参数：返回目标 destination id（0/缺省表示无，从 Profile 进入的场景）。 */
const val ARG_RETURN_DEST = "returnDestId"
const val ARG_RETURN_ARGS = "returnArgs"

/** 已登录 → 直接执行；未登录 → 跳 RSI 登录页并带当前页作为返回目标。 */
fun Fragment.requireRsiLogin(action: () -> Unit) {
    val loggedIn = RsiCookieStore.loadSession(requireContext()).isLoggedIn
    gateLogin(loggedIn, R.id.RsiLoginFragment, action)
}

/** 已登录 → 直接执行；未登录 → 跳 SCM 登录页并带当前页作为返回目标。 */
fun Fragment.requireScmLogin(action: () -> Unit) {
    gateLogin(AppServices.auth.isLoggedIn(), R.id.ScmLoginFragment, action)
}

private fun Fragment.gateLogin(loggedIn: Boolean, loginDest: Int, action: () -> Unit) {
    val current = findNavController().currentDestination?.id ?: 0
    val returnArgs = arguments?.let(::Bundle)
    when (val decision = LoginGate.decide(loggedIn, current, hasReturnArgs = returnArgs != null)) {
        LoginGate.Decision.Execute -> action()
        is LoginGate.Decision.Navigate -> navigateToLoginGated(loginDest, decision.returnDestId, returnArgs)
    }
}

/**
 * 跳登录页并把被门禁的当前页从返回栈弹掉（inclusive），避免"返回又触发门禁→死循环退不出"。
 * returnDestId 记录被门禁页，登录成功后用它重新进入。
 */
fun Fragment.navigateToLoginGated(loginDest: Int, gatedDestId: Int, gatedArgs: Bundle? = arguments?.let(::Bundle)) {
    val loginArgs = bundleOf(ARG_RETURN_DEST to gatedDestId).apply {
        gatedArgs?.let { putBundle(ARG_RETURN_ARGS, Bundle(it)) }
    }
    findNavController().navigate(
        loginDest,
        loginArgs,
        navOptions { if (gatedDestId != 0) popUpTo(gatedDestId) { inclusive = true } },
    )
}

/**
 * 登录页成功后调用：有返回目标则导航进入该页（同时把登录页弹出栈），否则只 pop 回上一页（Profile 场景）。
 */
fun Fragment.finishLogin() {
    val ret = arguments?.getInt(ARG_RETURN_DEST, 0) ?: 0
    val returnArgs = arguments?.getBundle(ARG_RETURN_ARGS)
    val nav = findNavController()
    if (ret != 0) {
        val loginId = nav.currentDestination?.id
        nav.navigate(
            ret,
            returnArgs,
            navOptions { if (loginId != null) popUpTo(loginId) { inclusive = true } },
        )
    } else {
        nav.popBackStack()
    }
}
