package com.euedrc.bugsc.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

class ChatUnreadState {
    private val mutableCount = MutableStateFlow(0)
    val count: StateFlow<Int> get() = mutableCount

    fun set(value: Int) {
        mutableCount.value = value.coerceAtLeast(0)
    }

    fun clear() {
        mutableCount.value = 0
    }
}

object ChatUnreadStore {
    private val state = ChatUnreadState()
    val count: StateFlow<Int> get() = state.count

    fun refresh(): Int {
        val value = ChatClient.unreadCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        state.set(value)
        return value
    }

    fun clear() = state.clear()
}

class ChatReconnectPolicy(
    private val maxAttempts: Int = 8,
    private val maxDelayMillis: Long = 30_000L,
) {
    fun shouldReconnect(foreground: Boolean, loggedIn: Boolean, attempt: Int): Boolean =
        foreground && loggedIn && attempt < maxAttempts

    fun delayMillis(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return min(maxDelayMillis, 1_000L * (1L shl shift))
    }
}

