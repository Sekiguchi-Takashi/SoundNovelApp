package com.sekiguchi.toaru

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * WebView でゲーム本体（assets/index.html）を表示する。
 *
 * 起動直後に落ちる事故を防ぐため、
 *  - onCreate 全体を try/catch で囲み、失敗したら原因を画面に出す
 *  - 端末差の大きい API（全画面化・向き固定）は失敗しても続行する
 * という作りにしている。
 */
class MainActivity : Activity() {

    private var web: WebView? = null
    private var lastBackAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: Throwable) {
        }

        try {
            val w = WebView(this)
            web = w

            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                textZoom = 100
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
            }

            w.isVerticalScrollBarEnabled = false
            w.isHorizontalScrollBarEnabled = false
            w.overScrollMode = View.OVER_SCROLL_NEVER
            w.setBackgroundColor(Color.parseColor("#07070A"))

            w.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e(TAG, "load error: ${request?.url}")
                }
            }

            w.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "${m.message()} @${m.lineNumber()}")
                    return true
                }
            }

            setContentView(w)
            w.loadUrl("file:///android_asset/index.html")

        } catch (t: Throwable) {
            Log.e(TAG, "起動に失敗", t)
            showError(t)
            return
        }

        // 全画面化は端末差が大きいので、失敗しても本体は動かす
        try {
            hideSystemBars()
        } catch (_: Throwable) {
        }
    }

    /** 例外の内容を画面に出す（黒画面のまま落ちるのを防ぐ） */
    private fun showError(t: Throwable) {
        val tv = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0A0E"))
            setTextColor(Color.parseColor("#E6DCCB"))
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setPadding(48, 96, 48, 48)
            text = buildString {
                append("起動に失敗しました\n\n")
                append(t::class.java.simpleName).append('\n')
                append(t.message ?: "").append("\n\n")
                t.stackTrace.take(12).forEach { append(it.toString()).append('\n') }
            }
        }
        val sv = ScrollView(this).apply { addView(tv) }
        setContentView(sv, FrameLayout.LayoutParams(-1, -1))
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try {
                hideSystemBars()
            } catch (_: Throwable) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        w.evaluateJavascript(
            "(function(){" +
                "try{" +
                "var p=document.querySelector('.panel.on');" +
                "if(p){p.classList.remove('on');return 'panel';}" +
                "var c=document.getElementById('choice');" +
                "if(c&&c.classList.contains('on'))return 'choice';" +
                "var t=document.getElementById('title');" +
                "if(t&&!t.classList.contains('hide'))return 'title';" +
                "if(typeof save==='function')save();" +
                "location.reload();return 'game';" +
                "}catch(e){return 'title';}})();"
        ) { result ->
            if (result.trim('"') == "title") {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    finish()
                } else {
                    lastBackAt = now
                    android.widget.Toast
                        .makeText(this, "もう一度押すと終了します", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            web?.evaluateJavascript("if(typeof save==='function')save();", null)
            web?.onPause()
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            web?.onResume()
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            web?.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Toaru"
    }
}
