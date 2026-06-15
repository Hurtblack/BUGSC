package com.euedrc.bugsc.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLauncherBadgeStateTest {

    @Test
    fun clearsNotificationWhenUnreadIsZero() {
        val state = ChatLauncherBadgeState.fromUnreadCount(0)

        assertFalse(state.shouldShowNotification)
        assertEquals(0, state.notificationNumber)
    }

    @Test
    fun showsNotificationForUnreadMessages() {
        val state = ChatLauncherBadgeState.fromUnreadCount(3)

        assertTrue(state.shouldShowNotification)
        assertEquals(3, state.notificationNumber)
        assertEquals("3 条未读消息", state.contentText)
    }

    @Test
    fun clampsNegativeUnreadToZero() {
        val state = ChatLauncherBadgeState.fromUnreadCount(-8)

        assertFalse(state.shouldShowNotification)
        assertEquals(0, state.notificationNumber)
    }
}
