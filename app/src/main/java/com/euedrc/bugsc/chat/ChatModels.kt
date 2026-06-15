package com.euedrc.bugsc.chat

import org.json.JSONObject

/** 聊天消息（REST 历史项 与 WS new-chat-message 的 content 同结构）。 */
data class ChatMessage(
    val id: Long,
    val fromUserId: Long,
    val toUserId: Long,
    val content: String,
    val fromUserNickname: String,
    val fromUserAvatar: String,
    val createTime: String,
    val clientMessageId: String,
) {
    fun isMine(currentUserId: Long): Boolean = fromUserId == currentUserId

    companion object {
        private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)

        fun parse(data: JSONObject): ChatMessage = ChatMessage(
            id = data.optLong("id"),
            fromUserId = data.optLong("fromUserId"),
            toUserId = data.optLong("toUserId"),
            content = data.str("content"),
            fromUserNickname = data.str("fromUserNickname"),
            fromUserAvatar = data.str("fromUserAvatar"),
            createTime = data.str("createTime"),
            clientMessageId = data.str("clientMessageId"),
        )
    }
}

/** 会话（conversations/create、conversations/list 项）。 */
data class ChatConversation(
    val id: Long,
    val otherUserId: Long,
    val otherUserNickname: String,
    val otherUserAvatar: String,
    val lastMessageContent: String,
    val lastMessageTime: String,
    val unreadCount: Int,
) {
    companion object {
        private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)

        fun parse(data: JSONObject): ChatConversation = ChatConversation(
            id = data.optLong("id"),
            otherUserId = data.optLong("otherUserId"),
            otherUserNickname = data.str("otherUserNickname"),
            otherUserAvatar = data.str("otherUserAvatar"),
            lastMessageContent = data.str("lastMessageContent"),
            lastMessageTime = data.str("lastMessageTime"),
            unreadCount = data.optInt("unreadCount", 0),
        )
    }
}
