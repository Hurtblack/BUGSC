package com.euedrc.bugsc

import android.view.MotionEvent
import android.webkit.WebView

object RsiWebViewSetup {
    fun configure(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = 100
        webView.settings.userAgentString = RsiCookieStore.USER_AGENT
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_ALWAYS
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    fun enableScrollableDialogs(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              document.documentElement.style.overflow = 'auto';
              document.body.style.overflow = 'auto';
              var nodes = document.querySelectorAll('[role="dialog"], .modal, .modal-content, .ReactModal__Content, [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]');
              for (var i = 0; i < nodes.length; i++) {
                nodes[i].style.maxHeight = '86vh';
                nodes[i].style.overflowY = 'auto';
                nodes[i].style.webkitOverflowScrolling = 'touch';
              }
            })();
            """.trimIndent(),
            null
        )
    }
}
