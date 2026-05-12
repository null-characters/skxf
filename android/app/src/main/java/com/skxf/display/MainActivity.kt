package com.skxf.display

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.net.URI
import java.net.URL
import java.util.Date
import java.util.Locale

/**
 * 大屏显示主界面 - 持续轮询广播模式（动态间隔优化）
 * 
 * 核心逻辑：
 * 1. 持续轮询 UDP 广播（动态间隔：离线5秒/同步10秒）
 * 2. 检测到广播 → 显示"同步中"，加载服务端页面
 * 3. 测不到广播 → 显示"离线中"，保持离线数据展示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    
    private var pollJob: Job? = null
    private var snapshotJob: Job? = null
    
    private var isConnected = false
    private var currentUrl: String? = null

    companion object {
        private const val TAG = "SkxfDisplay"
        private const val POLL_INTERVAL_ONLINE_MS = 10000L   // 同步中：10秒轮询
        private const val POLL_INTERVAL_OFFLINE_MS = 5000L   // 离线中：5秒轮询（快速恢复）
        private const val SNAPSHOT_INTERVAL_MS = 10000L      // 快照更新：10秒
        private const val DISCOVERY_TIMEOUT_MS = 2600
    }

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
        webView.setBackgroundColor(Color.parseColor("#FF6633"))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.d(TAG, "JS ${msg.messageLevel()}: ${msg.message()}")
                return true
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (!req.isForMainFrame) return
                handleLoadFailure(req.url?.toString().orEmpty(), err.description?.toString().orEmpty())
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                if (!req.isForMainFrame) return
                if (resp.statusCode in 200..299) return
                handleLoadFailure(req.url?.toString().orEmpty(), "HTTP ${resp.statusCode}")
            }
        }
    }

    private fun handleLoadFailure(failUrl: String, desc: String) {
        isConnected = false
        val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
        val json = prefs.getString(PrefKeys.OFFLINE_TABLE_JSON, "")?.trim().orEmpty()
        if (json.isNotBlank()) {
            showOfflinePage(json, prefs.getLong(PrefKeys.OFFLINE_SAVED_AT_MS, 0L))
        } else {
            showErrorPage(failUrl, desc)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        startPolling()
    }

    override fun onPause() {
        pollJob?.cancel(); pollJob = null
        snapshotJob?.cancel(); snapshotJob = null
        super.onPause()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            Log.i(TAG, "开始 UDP 广播轮询（动态间隔）")
            while (isActive) {
                val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
                val manualOnly = prefs.getBoolean(PrefKeys.MANUAL_ONLY, false)
                
                if (manualOnly) {
                    val url = prefs.getString(PrefKeys.MANUAL_URL, "")?.trim().orEmpty()
                    if (url.isNotBlank()) handleDiscoverySuccess(url, prefs)
                    else handleDiscoveryFailure(prefs)
                } else {
                    val result = discoverDashboardUrl(applicationContext, DISCOVERY_TIMEOUT_MS)
                    if (result != null) handleDiscoverySuccess(result.url, prefs)
                    else handleDiscoveryFailure(prefs)
                }
                // 动态间隔：同步中10秒，离线中5秒
                val interval = if (isConnected) POLL_INTERVAL_ONLINE_MS else POLL_INTERVAL_OFFLINE_MS
                delay(interval)
            }
        }
    }

    private fun handleDiscoverySuccess(url: String, prefs: SharedPreferences) {
        val urlChanged = url != currentUrl
        if (!isConnected || urlChanged) {
            Log.i(TAG, "服务端发现成功: $url")
            isConnected = true
            currentUrl = url
            prefs.edit().putString(PrefKeys.LAST_GOOD_URL, url).apply()
            runOnUiThread { webView.loadUrl(url) }
            startSnapshotLoop(prefs, url)
        }
    }

    private fun handleDiscoveryFailure(prefs: SharedPreferences) {
        if (isConnected) {
            Log.w(TAG, "服务端发现失败，切换离线模式")
            isConnected = false
            currentUrl = null
            val json = prefs.getString(PrefKeys.OFFLINE_TABLE_JSON, "")?.trim().orEmpty()
            if (json.isNotBlank()) {
                runOnUiThread { showOfflinePage(json, prefs.getLong(PrefKeys.OFFLINE_SAVED_AT_MS, 0L)) }
            } else {
                runOnUiThread { showNoServerPage() }
            }
        }
    }

    private fun showOfflinePage(json: String, savedAt: Long) {
        val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
        val retryUrl = prefs.getString(PrefKeys.LAST_GOOD_URL, "")?.trim().orEmpty()
        val apiUrl = buildApiTableStateUrl(retryUrl).orEmpty()
        webView.loadDataWithBaseURL(null, OfflinePageHtml.render(json, savedAt, apiUrl), "text/html", "UTF-8", null)
    }

    private fun showErrorPage(url: String, desc: String) {
        val safeUrl = url.replace("<", "&lt;")
        val safeDesc = desc.replace("<", "&lt;")
        webView.loadDataWithBaseURL(null, "<html><head><meta charset='utf-8'/></head><body style='margin:0;background:#FF6633;color:#fff;font-family:sans-serif;padding:28px'><h2>加载失败</h2><p>地址: <code>$safeUrl</code></p><p>原因: $safeDesc</p></body></html>", "text/html", "UTF-8", null)
    }

    private fun showNoServerPage() {
        webView.loadDataWithBaseURL(null, "<html><head><meta charset='utf-8'/></head><body style='margin:0;background:#FF6633;color:#fff;font-family:sans-serif;padding:28px'><h2>● 离线中 - 未发现服务端</h2><p>持续搜索局域网看板服务...</p><p style='opacity:0.8;font-size:14px;margin-top:20px'>轮询: 5秒(离线)/10秒(同步) | UDP端口: 39300</p></body></html>", "text/html", "UTF-8", null)
    }

    private fun startSnapshotLoop(prefs: SharedPreferences, displayUrl: String) {
        snapshotJob?.cancel()
        snapshotJob = lifecycleScope.launch {
            val apiUrl = buildApiTableStateUrl(displayUrl)
            if (apiUrl == null) return@launch
            delay(1200)
            while (isActive) {
                val dataRaw = fetchTableStateJson(apiUrl)
                val data = dataRaw.trim()
                if (data.isNotBlank()) {
                    try {
                        val obj = JSONObject(data)
                        if (obj.optJSONArray("columns") != null && obj.optJSONArray("rows") != null) {
                            prefs.edit().putString(PrefKeys.OFFLINE_TABLE_JSON, data).putLong(PrefKeys.OFFLINE_SAVED_AT_MS, System.currentTimeMillis()).apply()
                            Log.i(TAG, "离线快照已更新 bytes=${data.length}")
                        }
                    } catch (_: Exception) {}
                }
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    private fun buildApiTableStateUrl(displayUrl: String): String? = try {
        val u = URI(displayUrl)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null
        else "${u.scheme}://${u.host}:${if (u.port == -1) 80 else u.port}/api/table-state"
    } catch (_: Exception) { null }

    private suspend fun fetchTableStateJson(apiUrl: String): String = withContext(Dispatchers.IO) {
        try { (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 1500; readTimeout = 1500; useCaches = false }.inputStream.use { it.bufferedReader().readText() } }
        catch (_: Exception) { "" }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { if (hasFocus) hideSystemUi() }
}