package com.euedrc.bugsc.chat

/** Public build chat socket boundary. Real-time transport is supplied by private implementations. */
class ChatSocket(
    private val otherUserId: Long,
    private val onMessage: (ChatMessage) -> Unit,
    private val onStatus: (Boolean) -> Unit = {},
) {
    fun connect(wsUrl: String, ticket: String) {
        onStatus(false)
    }

    fun sendMessage(content: String, clientMessageId: String) = Unit

    fun close() = Unit

    companion object {
        const val WS_PATH = ""
    }
}
