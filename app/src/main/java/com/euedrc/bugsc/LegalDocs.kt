package com.euedrc.bugsc

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.webkit.WebView

/** 法律文档常量与弹窗展示（同意流程内查看全文用） */
object LegalDocs {
    const val PRIVACY = "privacy_policy.html"
    const val AGREEMENT = "user_agreement.html"
    const val DISCLAIMER = "disclaimer.html"

    fun assetUrl(doc: String) = "file:///android_asset/legal/$doc"

    /** 二级弹窗内嵌 WebView 展示全文，不离开当前流程 */
    fun showDialog(context: Context, doc: String, title: String) {
        val webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            loadUrl(assetUrl(doc))
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(webView)
            .setPositiveButton("关闭", null)
            .show()
    }
}
