package com.euedrc.bugsc

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class CharacterRepairFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var btnReload: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_character_repair, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.web_repair)
        tvStatus = view.findViewById(R.id.tv_repair_status)
        btnReload = view.findViewById(R.id.btn_repair_reload)
        btnBack = view.findViewById(R.id.btn_repair_back)
        btnForward = view.findViewById(R.id.btn_repair_forward)

        RsiWebViewSetup.configure(webView)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tvStatus.text = "正在打开 RSI 角色修复页..."
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                RsiWebViewSetup.enableScrollableDialogs(view)
                tvStatus.text = "已打开页面。登录有效时可依次点击 CHARACTER REPAIR 和 Request repair。"
                updateNavButtons()
            }
        }

        btnReload.setOnClickListener { loadRepairPage() }
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            updateNavButtons()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
            updateNavButtons()
        }

        loadRepairPage()
    }

    private fun loadRepairPage() {
        val session = RsiCookieStore.injectIntoWebView(requireContext(), webView)
        if (!session.isLoggedIn) {
            tvStatus.text = "未找到 RSI 登录状态。请先进入“库存查看”登录，再回来使用角色修复。"
        } else {
            tvStatus.text = "已注入 RSI 登录状态，正在打开角色修复页..."
        }
        webView.loadUrl(RsiCookieStore.ACCOUNT_RESET_URL)
        updateNavButtons()
    }

    private fun updateNavButtons() {
        btnBack.isEnabled = webView.canGoBack()
        btnForward.isEnabled = webView.canGoForward()
    }

    override fun onDestroyView() {
        webView.stopLoading()
        super.onDestroyView()
    }
}
