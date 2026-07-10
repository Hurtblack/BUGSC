package com.euedrc.bugsc.chat

/** Public build chat boundary. Real chat transport belongs to a private data-source implementation. */
object ChatClient {
    fun createConversation(otherUserId: Long): ChatConversation? = null
    fun conversations(limit: Int = 50): List<ChatConversation> = emptyList()
    fun unreadCount(): Long = 0L
    fun publicProfile(userId: Long): ChatPublicProfile =
        ChatPublicProfile(userId, "", "", 0, emptyList(), "", "", "")
    fun markRead(conversationId: Long) = Unit
    fun history(otherUserId: Long, lastMessageId: Long? = null, pageSize: Int = 30): List<ChatMessage> = emptyList()
    fun resolveWsBaseUrl(): String = ""
    fun wsTicket(): String? = null
}
