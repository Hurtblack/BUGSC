package com.euedrc.bugsc

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
        val notes = release.notes.takeIf { it.isNotBlank() } ?: "暂无更新说明"
        val builder = AlertDialog.Builder(context)
            .setTitle("发现新版本")
            .setMessage("当前版本：$currentVersion\n最新版本：${release.versionName}\n\n$notes")
            .setPositiveButton("下载更新") { _, _ ->
                val apkUrl = release.apkUrl
                if (apkUrl != null) {
                    downloadApk(context, apkUrl, release.versionName, release.pageUrl)
                } else {
                    openInBrowser(context, release.pageUrl)
                }
            }
            .setNegativeButton("取消", null)
        if (onIgnore != null) {
            builder.setNeutralButton("忽略此版本") { _, _ -> onIgnore() }
        }
        builder.show()
    }

    /**
     * 用 DownloadManager 下载并强制 APK 的 MIME 与文件名。
     * Gitee 附件服务按 application/zip 返回 APK，交给浏览器会被改名成 .apk.zip，
     * 这里固定 MIME 后下载完成点通知即可直接安装。
     */
    private fun downloadApk(context: Context, apkUrl: String, versionName: String, fallbackPageUrl: String) {
        runCatching {
            val fileName = "SCMobiGlas-release-v${versionName.removePrefix("v")}.apk"
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setMimeType("application/vnd.android.package-archive")
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "开始下载新版本，完成后点击通知栏安装", Toast.LENGTH_LONG).show()
        }.onFailure {
            openInBrowser(context, fallbackPageUrl)
        }
    }

    private fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "无法打开下载链接", Toast.LENGTH_SHORT).show()
        }
    }
}
