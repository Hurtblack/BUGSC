package com.euedrc.bugsc

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 「发现新版本」弹窗，MainActivity 自动检查与 ProfileFragment 手动检查共用 */
object AppUpdateNotifier {

    /**
     * @param onIgnore 非空时显示「忽略此版本」按钮（自动检查场景）；手动检查传 null
     */
    fun showUpdateDialog(
        context: Context,
        currentVersion: String,
        release: AppUpdateClient.ReleaseInfo,
        onIgnore: (() -> Unit)? = null,
    ) {
        val targetUrl = release.apkUrl ?: release.pageUrl
        val notes = release.notes.takeIf { it.isNotBlank() } ?: "暂无更新说明"
        val builder = AlertDialog.Builder(context)
            .setTitle("发现新版本")
            .setMessage("当前版本：$currentVersion\n最新版本：${release.versionName}\n\n$notes")
            .setPositiveButton("下载更新") { _, _ ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                }.onFailure {
                    Toast.makeText(context, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
        if (onIgnore != null) {
            builder.setNeutralButton("忽略此版本") { _, _ -> onIgnore() }
        }
        builder.show()
    }
}
