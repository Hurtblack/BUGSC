package com.euedrc.bugsc.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInboxTest {

    @Test
    fun parsesConversationListAndUnreadCount() {
        val list = ChatParser.parseConversations(
            JSONObject(
                """
                {"code":0,"data":[{
                  "id":7,
                  "otherUserId":555,
                  "otherUserNickname":"流浪",
                  "lastMessageContent":"在吗",
                  "lastMessageTime":"2026-06-15 12:00:00",
                  "unreadCount":3
                }]}
                """.trimIndent()
            )
        )
        assertEquals(1, list.size)
        assertEquals(3, list.single().unreadCount)
        assertEquals(5L, ChatParser.parseUnread(JSONObject("""{"code":0,"data":5}""")))
    }

    @Test
    fun unreadStateRefreshesAndClears() {
        val state = ChatUnreadState()
        state.set(4)
        assertEquals(4, state.count.value)
        state.clear()
        assertEquals(0, state.count.value)
    }

    @Test
    fun reconnectPolicyStopsInBackgroundAndCapsDelay() {
        val policy = ChatReconnectPolicy()
        assertTrue(policy.shouldReconnect(foreground = true, loggedIn = true, attempt = 0))
        assertFalse(policy.shouldReconnect(foreground = false, loggedIn = true, attempt = 0))
        assertFalse(policy.shouldReconnect(foreground = true, loggedIn = false, attempt = 0))
        assertEquals(1_000L, policy.delayMillis(0))
        assertEquals(30_000L, policy.delayMillis(20))
    }
}

