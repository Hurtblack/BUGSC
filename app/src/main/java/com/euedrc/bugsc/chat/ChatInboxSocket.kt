package com.euedrc.bugsc.chat

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ChatInboxSocket(
    private val onMessage: () -> Unit,
    private val onStatus: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var heartbeat: Runnable? = null
    private var closed = false

    fun connect(baseUrl: String, ticket: String) {
        closed = false
        val base = baseUrl.trimEnd('/')
        val url = "$base${ChatSocket.WS_PATH}?ticket=${URLEncoder.encode(ticket, "UTF-8")}"
            .replaceFirst(Regex("^wss://"), "https://")
            .replaceFirst(Regex("^ws://"), "http://")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startHeartbeat()
                main.post { onStatus(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
                if (type == "new-chat-message") main.post(onMessage)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                main.post { onStatus(false) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                main.post { onStatus(false) }
            }
        })
    }

    fun close() {
        closed = true
        stopHeartbeat()
        socket?.close(1000, null)
        socket = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val task = object : Runnable {
            override fun run() {
                if (closed) return
                val content = JSONObject().put("timestamp", System.currentTimeMillis()).toString()
                socket?.send(JSONObject().put("type", "chat-heartbeat").put("content", content).toString())
                main.postDelayed(this, HEARTBEAT_MILLIS)
            }
        }
        heartbeat = task
        main.postDelayed(task, HEARTBEAT_MILLIS)
    }

    private fun stopHeartbeat() {
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
    }

    private companion object {
        const val HEARTBEAT_MILLIS = 25_000L
    }
}
