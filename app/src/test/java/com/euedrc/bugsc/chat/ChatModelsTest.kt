package com.euedrc.bugsc.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModelsTest {

    @Test
    fun parsesMessage() {
        val json = """
            {"id":1001,"fromUserId":555,"toUserId":42,"content":"在吗",
             "fromUserNickname":"流浪","fromUserAvatar":"https://a/x.png",
             "isRead":0,"createTime":"2026-06-14 12:00:00"}
        """.trimIndent()
        val m = ChatMessage.parse(JSONObject(json))
        assertEquals(1001L, m.id)
        assertEquals(555L, m.fromUserId)
        assertEquals(42L, m.toUserId)
        assertEquals("在吗", m.content)
        assertEquals("流浪", m.fromUserNickname)
        assertEquals("2026-06-14 12:00:00", m.createTime)
    }

    @Test
    fun mineByCurrentUser() {
        val m = ChatMessage.parse(JSONObject("""{"id":1,"fromUserId":42,"toUserId":555,"content":"hi"}"""))
        assertEquals(true, m.isMine(currentUserId = 42))
        assertEquals(false, m.isMine(currentUserId = 555))
    }

    @Test
    fun parsesConversation() {
        val json = """
            {"id":7,"otherUserId":555,"otherUserNickname":"流浪","otherUserAvatar":"https://a/x.png",
             "lastMessageContent":"在吗","lastMessageTime":"2026-06-14 12:00:00","unreadCount":3}
        """.trimIndent()
        val c = ChatConversation.parse(JSONObject(json))
        assertEquals(7L, c.id)
        assertEquals(555L, c.otherUserId)
        assertEquals("流浪", c.otherUserNickname)
        assertEquals(3, c.unreadCount)
    }
}
