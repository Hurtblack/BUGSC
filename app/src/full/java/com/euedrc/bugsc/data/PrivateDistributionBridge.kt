package com.euedrc.bugsc.data

import android.content.Context

object PrivateDistributionBridge {
    fun installIfPresent(context: Context) {
        runCatching {
            val clazz = Class.forName("com.euedrc.bugsc.privateimpl.PrivateDistributionServices")
            val method = clazz.getMethod("install", Context::class.java)
            method.invoke(null, context)
        }
    }
}
