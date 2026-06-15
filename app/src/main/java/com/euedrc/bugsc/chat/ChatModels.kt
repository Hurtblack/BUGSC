package com.euedrc.bugsc.chat

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    val otherUserSignInStatus: Int = 0,
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
            otherUserSignInStatus = data.optInt("otherUserSignInStatus", 0),
        )
    }
}

data class ChatPublicProfile(
    val id: Long,
    val nickname: String,
    val avatar: String,
    val signInStatus: Int,
    val tradeDays: List<Boolean>,
    val tradeStartTime: String,
    val tradeEndTime: String,
    val contact: String,
)

object ChatProfileFormatter {
    fun tradeSchedule(days: List<Boolean>, start: String, end: String): String {
        val dayText = when {
            days.size != 7 || days.none { it } -> "未设置"
            days.all { it } -> "每天"
            else -> listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                .filterIndexed { index, _ -> days[index] }
                .joinToString("、")
        }
        val timeText = if (start.isBlank() || end.isBlank()) "未设置" else "$start - $end"
        return "交易时间：$timeText（$dayText）UTC+8"
    }

    fun contact(value: String): String =
        "联系方式：${value.trim().ifBlank { "未公开" }}"

    fun displayName(vararg candidates: String): String {
        val values = candidates.map(String::trim).filter(String::isNotBlank)
        return values.firstOrNull { '*' !in it } ?: values.firstOrNull().orEmpty()
    }

    fun messageTime(value: String, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (value.isBlank()) return "刚刚"
        value.toLongOrNull()?.let { timestamp ->
            val millis = if (value.length <= 10) timestamp * 1_000 else timestamp
            return SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                this.timeZone = timeZone
            }.format(Date(millis))
        }
        return TIME_PATTERN.find(value)?.groupValues?.getOrNull(1).orEmpty().ifBlank { value }
    }

    private val TIME_PATTERN = Regex("""\b(\d{2}:\d{2})(?::\d{2})?\b""")
}

object ChatParser {
    fun parseConversations(json: JSONObject): List<ChatConversation> {
        requireSuccess(json)
        val array = json.optJSONArray("data") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(ChatConversation::parse)
        }
    }

    fun parseUnread(json: JSONObject): Long {
        requireSuccess(json)
        return json.optLong("data", 0L)
    }

    fun parsePublicProfile(json: JSONObject): ChatPublicProfile {
        requireSuccess(json)
        val data = json.getJSONObject("data")
        val tradeTime = when (val raw = data.opt("tradeTime")) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        }
        val days = if (tradeTime == null) emptyList() else {
            (0 until tradeTime.length()).map { tradeTime.optInt(it) == 1 }
        }
        return ChatPublicProfile(
            id = data.optLong("id"),
            nickname = data.optString("nickname"),
            avatar = data.optString("avatar"),
            signInStatus = data.optInt("signInStatus"),
            tradeDays = days,
            tradeStartTime = data.optString("tradeStartTime"),
            tradeEndTime = data.optString("tradeEndTime"),
            contact = data.optString("userMobile"),
        )
    }

    private fun requireSuccess(json: JSONObject) {
        if (json.optInt("code", -1) != 0) {
            throw IllegalStateException(json.optString("msg").ifBlank { "聊天接口请求失败" })
        }
    }
}
