package com.euedrc.bugsc.chat

class ChatLauncherBadgeState private constructor(
    val notificationNumber: Int,
) {
    val shouldShowNotification: Boolean get() = notificationNumber > 0
    val contentText: String get() = "${notificationNumber} 条未读消息"

    companion object {
        fun fromUnreadCount(count: Int): ChatLauncherBadgeState =
            ChatLauncherBadgeState(count.coerceAtLeast(0))
    }
}
