package com.euedrc.bugsc.chat

import com.euedrc.bugsc.scm.ScmAuthStore
import com.euedrc.bugsc.scm.ScmResponse
import org.json.JSONObject

/** 聊天 REST 接口（走 ScmApi：自动带 token + 401/业务401 续期）。 */
object ChatClient {

    private fun api() = ScmAuthStore.api()

    /** 创建或恢复会话。 */
    fun createConversation(otherUserId: Long): ChatConversation? {
        val body = JSONObject().put("otherUserId", otherUserId).toString()
        val resp = api().request("POST", "/member/chat/conversations/create", body)
        if (resp.code !in 200..299) return null
        val parsed = ScmResponse.parse(resp.body)
        return parsed.data?.let { ChatConversation.parse(it) }
    }

    /** 聊天记录（按时间正序返回，便于直接 append 到列表底部）。 */
    fun history(otherUserId: Long, lastMessageId: Long? = null, pageSize: Int = 30): List<ChatMessage> {
        val sb = StringBuilder("/member/chat/messages/history?otherUserId=$otherUserId&pageSize=$pageSize")
        if (lastMessageId != null) sb.append("&lastMessageId=").append(lastMessageId)
        val resp = api().request("GET", sb.toString())
        android.util.Log.d("ChatClient", "history http=${resp.code} body=${resp.body.take(300)}")
        if (resp.code !in 200..299) return emptyList()
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: return emptyList()
        if (json.optInt("code", -1) != 0) return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ChatMessage.parse(it) } }
        // 后端可能倒序返回，按 id 升序排，旧消息在前。
        return list.sortedBy { it.id }
    }

    /**
     * 解析 WebSocket 连接域名。官网按地区动态下发：GET /app-api/region（公开、无需登录，
     * 由 scms.flowcld.com 引导域提供）返回 wsHost。各地区都有专用 ws 子域：海外如 scmk.flowcld.top，
     * 国内为 ws.flowcld.xyz（注意不是裸 flowcld.xyz，那是网站）。直接信任 wsHost，空才回退。
     */
    fun resolveWsBaseUrl(): String {
        val fallback = "https://flowcld.xyz"
        return runCatching {
            val conn = (java.net.URL("https://scms.flowcld.com/app-api/region").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode !in 200..299) return@runCatching fallback
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val wsHost = o.optString("wsHost").ifEmpty { o.optString("apiHost") }
            if (wsHost.isEmpty()) fallback else "https://$wsHost"
        }.getOrDefault(fallback)
    }

    /** WebSocket 一次性票据。 */
    fun wsTicket(): String? {
        val resp = api().request("POST", "/member/chat/ws-ticket")
        if (resp.code !in 200..299) return null
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 0) return null
        return json.optString("data").ifEmpty { null }
    }
}
