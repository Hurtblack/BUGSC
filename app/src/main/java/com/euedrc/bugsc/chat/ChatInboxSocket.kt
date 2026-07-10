package com.euedrc.bugsc.chat

/** Public build inbox socket boundary. Real-time transport is supplied by private implementations. */
class ChatInboxSocket(
    private val onMessage: () -> Unit,
    private val onStatus: (Boolean) -> Unit,
) {
    fun connect(baseUrl: String, ticket: String) {
        onStatus(false)
    }

    fun close() = Unit
}
