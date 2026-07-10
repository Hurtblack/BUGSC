package com.euedrc.bugsc.wbccu

import android.content.Context

class WbccuChainStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadChains(): List<WbccuChain> {
        return WbccuChainCodec.decodeChains(prefs.getString(KEY_CHAINS, "") ?: "")
    }

    fun saveChains(chains: List<WbccuChain>) {
        prefs.edit()
            .putString(KEY_CHAINS, WbccuChainCodec.encodeChains(chains))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "wbccu"
        private const val KEY_CHAINS = "wbccu_chains_v1"
    }
}
