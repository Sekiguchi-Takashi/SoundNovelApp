package com.sekiguchi.toaru

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var lastBackAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true      // data/*.json の読み込みに必要
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(0xFF07070A.toInt())
            // 端末によっては大きな画像でGPU描画が不安定になるため保険
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    msg: android.webkit.ConsoleMessage
                ): Boolean {
                    android.util.Log.d("Toaru",
                        "${msg.message()} @${msg.lineNumber()}")
                    return true
                }
            }
        }

        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){" +
            "var p=document.querySelector('.panel.on');" +
            "if(p){p.classList.remove('on');return 'panel';}" +
            "var c=document.getElementById('choice');" +
            "if(c&&c.classList.contains('on'))return 'choice';" +
            "var t=document.getElementById('title');" +
            "if(t&&!t.classList.contains('hide'))return 'title';" +
            "if(typeof save==='function')save();" +
            "location.reload();return 'game';})();"
        ) { result ->
            if (result.trim('"') == "title") {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(this, "もう一度押すと終了します", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("if(typeof save==='function')save();", null)
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
