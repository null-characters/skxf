package com.skxf.display

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        prepareWebView()
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepareWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        scheduleLoad()
    }

    override fun onPause() {
        loadJob?.cancel()
        loadJob = null
        super.onPause()
    }

    private fun scheduleLoad() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
            val raw = resolveUrlString(prefs)
            val url = normalizeHttpUrl(raw)
            when {
                url == null -> webView.loadDataWithBaseURL(
                    null,
                    "<html><head><meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width\"/></head>" +
                        "<body style=\"margin:0;background:#FF6633;color:#fff;font-family:sans-serif;padding:28px;line-height:1.5\">" +
                        "<h2 style=\"margin-top:8px\">未找到大屏地址</h2>" +
                        "<p>请先在电脑上启动看板服务；本机会向局域网广播 UDP <code style=\"background:rgba(0,0,0,.25);padding:2px 6px;border-radius:4px\">SKXF_DISCOVER</code> 并接收服务器回复。</p>" +
                        "<p>若仍不可用，请点击左上角 ⚙️ 填入 <code>http://电脑IP:3000/?mode=display</code>。</p></body></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )

                else -> {
                    prefs.edit().putString(PrefKeys.LAST_GOOD_URL, url).apply()
                    webView.loadUrl(url)
                }
            }
        }
    }

    /** 首选 UDP 发现的源 IP + 端口 + path；失败后用手动兜底，再后是上次成功的缓存。 */
    private suspend fun resolveUrlString(prefs: SharedPreferences): String {
        val manualOnly = prefs.getBoolean(PrefKeys.MANUAL_ONLY, false)
        val manual = prefs.getString(PrefKeys.MANUAL_URL, "")?.trim().orEmpty()
        val cached = prefs.getString(PrefKeys.LAST_GOOD_URL, "")?.trim().orEmpty()

        if (manualOnly) {
            return when {
                manual.isNotBlank() -> manual
                else -> cached
            }
        }

        val discovered = discoverDashboardUrl(applicationContext, 2600)?.trim().orEmpty()
        return when {
            discovered.isNotBlank() -> discovered
            manual.isNotBlank() -> manual
            else -> cached
        }
    }

    private fun normalizeHttpUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isBlank()) return null
        return u.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }
}
