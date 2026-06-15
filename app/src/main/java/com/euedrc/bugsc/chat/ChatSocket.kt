package com.euedrc.bugsc.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 聊天 WebSocket（OkHttp）。协议（与官网一致，信封 {type, content:JSON字符串}）：
 *  - 连接：wsUrl?ticket=<一次性票据>
 *  - 订阅会话：chat-subscribe {otherUserId}
 *  - 发消息：chat-send {toUserId, content, clientMessageId}
 *  - 心跳：chat-heartbeat {timestamp}
 *  - 收消息：new-chat-message，content 为 ChatMessage JSON
 * 回调统一切回主线程。
 */
class ChatSocket(
    private val otherUserId: Long,
    private val onMessage: (ChatMessage) -> Unit,
    private val onStatus: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // 用应用层 chat-heartbeat
        .build()
    private var ws: WebSocket? = null
    private var heartbeat: Runnable? = null
    private var closed = false

    fun connect(wsUrl: String, ticket: String) {
        val base = normalizeUrl(wsUrl)
        val sep = if (base.contains("?")) "&" else "?"
        // 与官网一致：一次性 ws-ticket 放进 ticket 查询参数鉴权。
        val url = "$base${sep}ticket=${URLEncoder.encode(ticket, "UTF-8")}"
        Log.d(TAG, "ws connect $url")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "ws open")
                subscribe()
                startHeartbeat()
                main.post { onStatus(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.body?.string() }.getOrNull()
                Log.d(TAG, "ws failure: ${t.message} httpCode=${response?.code} body=${body?.take(400)}")
                stopHeartbeat()
                main.post { onStatus(false) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                main.post { onStatus(false) }
            }
        })
    }

    fun sendMessage(content: String, clientMessageId: String) {
        val c = JSONObject()
            .put("toUserId", otherUserId)
            .put("content", content)
            .put("clientMessageId", clientMessageId)
        sendEnvelope("chat-send", c.toString())
    }

    fun close() {
        closed = true
        stopHeartbeat()
        runCatching { ws?.close(1000, null) }
        ws = null
    }

    /** temp-token 的 wsUrl 是相对路径 /infra/...，需补到与 REST 同一个 API base（含 /app-api），
     *  否则会落到网站根域返回首页 HTML 而非 WebSocket 升级；并把 ws/wss 换成 OkHttp 要的 http/https。 */
    private fun normalizeUrl(wsUrl: String): String {
        var u = wsUrl.trim()
        if (u.startsWith("/")) u = HOST + u
        u = u.replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
        return u
    }

    private fun subscribe() {
        sendEnvelope("chat-subscribe", JSONObject().put("otherUserId", otherUserId).toString())
    }

    private fun sendEnvelope(type: String, contentJson: String) {
        val env = JSONObject().put("type", type).put("content", contentJson).toString()
        ws?.send(env)
    }

    private fun handleIncoming(text: String) {
        Log.d(TAG, "ws recv ${text.take(200)}")
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (obj.optString("type") != "new-chat-message") return
        val contentObj = when (val c = obj.opt("content")) {
            is JSONObject -> c
            is String -> runCatching { JSONObject(c) }.getOrNull()
            else -> null
        } ?: return
        val msg = ChatMessage.parse(contentObj)
        main.post { onMessage(msg) }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val r = object : Runnable {
            override fun run() {
                if (closed) return
                sendEnvelope("chat-heartbeat", JSONObject().put("timestamp", System.currentTimeMillis()).toString())
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = r
        main.postDelayed(r, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        heartbeat?.let { main.removeCallbacks(it) }
        heartbeat = null
    }

    companion object {
        private const val TAG = "ChatSocket"
        private const val HEARTBEAT_MS = 25_000L
        // infra WebSocket 相对路径；实际 base 由 ChatClient.resolveWsBaseUrl() 按地区动态拼成绝对地址。
        const val WS_PATH = "/infra/ws"
        // 仅当传入相对路径时兜底（正常走绝对地址，不会用到）。
        private const val HOST = "https://flowcld.xyz"
    }
}
