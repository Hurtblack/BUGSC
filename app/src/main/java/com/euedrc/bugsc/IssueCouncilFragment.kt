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

class IssueCouncilFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var btnReload: Button
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_issue_council, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.web_issue_council)
        tvStatus = view.findViewById(R.id.tv_issue_status)
        btnReload = view.findViewById(R.id.btn_issue_reload)
        btnBack = view.findViewById(R.id.btn_issue_back)
        btnForward = view.findViewById(R.id.btn_issue_forward)

        RsiWebViewSetup.configure(webView)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tvStatus.text = "正在打开 Issue Council..."
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                RsiWebViewSetup.enableScrollableDialogs(view)
                tvStatus.text = "IC 页面已打开，反馈模板已经在剪贴板，可直接粘贴。"
                updateNavButtons()
            }
        }

        btnReload.setOnClickListener { loadIssueCouncil() }
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            updateNavButtons()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
            updateNavButtons()
        }

        loadIssueCouncil()
    }

    private fun loadIssueCouncil() {
        val session = RsiCookieStore.injectIntoWebView(requireContext(), webView)
        tvStatus.text = if (session.isLoggedIn) {
            "已注入 RSI 登录状态，正在打开 Issue Council..."
        } else {
            "未找到 RSI 登录状态。可继续打开 IC，也可先去库存页登录。"
        }
        webView.loadUrl(BugIssueCouncilFormatter.ISSUE_COUNCIL_URL)
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
