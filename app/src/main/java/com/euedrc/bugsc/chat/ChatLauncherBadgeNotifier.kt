package com.euedrc.bugsc.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.euedrc.bugsc.MainActivity
import com.euedrc.bugsc.R

object ChatLauncherBadgeNotifier {
    private const val CHANNEL_ID = "scm_chat_unread"
    private const val NOTIFICATION_ID = 1107

    fun sync(context: Context, unreadCount: Int) {
        val appContext = context.applicationContext
        val state = ChatLauncherBadgeState.fromUnreadCount(unreadCount)
        val manager = NotificationManagerCompat.from(appContext)
        if (!state.shouldShowNotification) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!canPostNotifications(appContext)) return
        ensureChannel(appContext)
        manager.notify(NOTIFICATION_ID, buildNotification(appContext, state))
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        NotificationChannel(
            CHANNEL_ID,
            "SCM 消息",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "用于显示 SCM 未读消息和桌面图标红点"
            setShowBadge(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildNotification(context: Context, state: ChatLauncherBadgeState) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle("SCM 新消息")
            .setContentText(state.contentText)
            .setNumber(state.notificationNumber)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(context))
            .build()

    private fun openAppIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
