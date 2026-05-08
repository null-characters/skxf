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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.net.URI
import java.net.URL
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var loadJob: Job? = null
    private var snapshotJob: Job? = null

    companion object {
        private const val TAG = "SkxfDisplay"
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
        // WebView 默认白底；加载失败或未绘制首帧时否则会像「白屏」
        webView.setBackgroundColor(Color.parseColor("#FF6633"))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        webView.settings.mixedContentMode =
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                        "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                val failUrl = request.url?.toString().orEmpty()
                val desc = error.description?.toString().orEmpty()
                Log.e(TAG, "主文档加载失败 url=$failUrl error=$desc")
                if (!showOfflineIfAvailable(failUrl, desc)) {
                    view.loadDataWithBaseURL(
                        null,
                        mainFrameErrorHtml(failUrl, desc),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                if (code in 200..299) return
                val failUrl = request.url?.toString().orEmpty()
                val desc = "HTTP $code"
                Log.e(TAG, "主文档 HTTP 错误 url=$failUrl $desc")
                if (!showOfflineIfAvailable(failUrl, desc)) {
                    view.loadDataWithBaseURL(
                        null,
                        mainFrameErrorHtml(failUrl, desc),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            }
        }
    }

    private fun showOfflineIfAvailable(failedUrl: String, description: String): Boolean {
        val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
        val json = prefs.getString(PrefKeys.OFFLINE_TABLE_JSON, "")?.trim().orEmpty()
        if (json.isBlank()) return false
        val savedAt = prefs.getLong(PrefKeys.OFFLINE_SAVED_AT_MS, 0L)
        val retryDisplayUrl = prefs.getString(PrefKeys.LAST_GOOD_URL, "")?.trim().orEmpty()
            .ifBlank { failedUrl.trim() }
        val retryApiUrl = buildApiTableStateUrl(retryDisplayUrl).orEmpty()
        Log.w(TAG, "切换离线展示 savedAt=$savedAt failUrl=$failedUrl reason=$description")
        webView.loadDataWithBaseURL(
            null,
            offlineHtml(
                tableJson = json,
                savedAtMs = savedAt,
                failedUrl = failedUrl,
                description = description,
                retryDisplayUrl = retryDisplayUrl,
                retryApiUrl = retryApiUrl,
            ),
            "text/html",
            "UTF-8",
            null,
        )
        return true
    }

    private fun formatSavedAt(ms: Long): String {
        if (ms <= 0) return "未知"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return fmt.format(Date(ms))
    }

    private fun offlineHtml(
        tableJson: String,
        savedAtMs: Long,
        failedUrl: String,
        description: String,
        retryDisplayUrl: String,
        retryApiUrl: String,
    ): String {
        // 以 JS 渲染表格，避免在 Kotlin 里做复杂拼接；并把状态指示改为「离线中」。
        val safeFailUrl = failedUrl.replace("<", "&lt;")
        val safeDesc = description.replace("<", "&lt;")
        val savedAtText = formatSavedAt(savedAtMs)
        val safeSavedAt = savedAtText.replace("<", "&lt;")
        val safeRetryDisplayUrl = retryDisplayUrl.replace("<", "&lt;")
        val safeRetryApiUrl = retryApiUrl.replace("<", "&lt;")
        return """
            <html>
              <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover, user-scalable=no"/>
                <title>数据看板（离线）</title>
                <style>
                  * { box-sizing: border-box; }
                  body { margin:0; background:#FF5000; color:rgba(255,255,255,.96); font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; overflow:hidden; }
                  .wrap { height:100vh; height:100dvh; display:flex; flex-direction:column; padding:6px; }
                  .header { flex: 0 0 auto; padding:14px 24px; border-bottom:2px solid rgba(255,255,255,.28); background: rgba(0,0,0,.08); position:relative; }
                  .title { font-size:28px; font-weight:700; text-align:center; text-shadow: 0 2px 12px rgba(0,0,0,.28); }
                  .meta { position:absolute; right:18px; top:14px; font-size:13px; color:rgba(255,248,240,.82); text-align:right; line-height:1.35; }
                  .table { flex:1 1 0%; min-height:0; overflow:hidden; padding:6px 0 2px 0; }
                  .glass { height:100%; border:1px solid rgba(255,255,255,.28); border-radius:14px; background: rgba(0,0,0,.18); overflow:hidden; }
                  table { width:100%; border-collapse:collapse; table-layout:fixed; }
                  thead th { padding:12px 10px; font-size:16px; font-weight:700; border-bottom:1px solid rgba(255,255,255,.28); background: rgba(0,0,0,.22); text-align:center; }
                  tbody td { padding:12px 10px; font-size:15px; border-bottom:1px solid rgba(255,255,255,.12); text-align:center; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
                  tbody tr:nth-child(even) td { background: rgba(0,0,0,.10); }
                  .status { flex:0 0 auto; font-size:13px; padding:10px 18px; color:rgba(255,248,240,.82); text-shadow: 0 1px 2px rgba(0,0,0,.2); }
                  code { background: rgba(0,0,0,.25); padding:2px 6px; border-radius:4px; }
                </style>
              </head>
              <body>
                <div class="wrap">
                  <div class="header">
                    <div class="title">BILLDA研发项目管理看板</div>
                    <div class="meta">
                      <div>离线快照：${safeSavedAt}</div>
                      <div style="opacity:.9; font-size:12px; margin-top:4px;">无法连接：${safeFailUrl}</div>
                      <div style="opacity:.9; font-size:12px;">原因：${safeDesc}</div>
                      <div style="opacity:.85; font-size:12px; margin-top:6px;">自动重连：${safeRetryApiUrl}</div>
                    </div>
                  </div>
                  <div class="table">
                    <div class="glass">
                      <div style="overflow:auto; height:100%;">
                        <table id="t"><thead id="th"></thead><tbody id="tb"></tbody></table>
                      </div>
                    </div>
                  </div>
                  <div class="status" id="status">● 离线中</div>
                </div>
                <script>
                  const data = ${tableJson};
                  const columns = (data && Array.isArray(data.columns)) ? data.columns : [];
                  const rows = (data && Array.isArray(data.rows)) ? data.rows : [];
                  const th = document.getElementById('th');
                  const tb = document.getElementById('tb');
                  th.innerHTML = '<tr>' + columns.map(c => `<th>${'$'}{String(c ?? '')}</th>`).join('') + '</tr>';
                  const maxRows = 19;
                  const showRows = rows.slice(0, Math.min(rows.length, maxRows));
                  tb.innerHTML = showRows.map(r => {
                    const cells = Array.isArray(r.data) ? r.data : [];
                    return '<tr>' + columns.map((_, i) => `<td>${'$'}{String(cells[i] ?? '')}</td>`).join('') + '</tr>';
                  }).join('');
                  const status = document.getElementById('status');
                  status.textContent = `● 离线中（自动重连中…） | 1-${'$'}{showRows.length}/${'$'}{rows.length}`;

                  const retryDisplayUrl = ${"\"\"\""}${safeRetryDisplayUrl}${"\"\"\""};
                  const retryApiUrl = ${"\"\"\""}${safeRetryApiUrl}${"\"\"\""};
                  function probeOnce() {
                    if (!retryApiUrl) return;
                    const controller = new AbortController();
                    const t = setTimeout(() => controller.abort(), 1600);
                    fetch(retryApiUrl, { cache: 'no-store', signal: controller.signal })
                      .then(r => { clearTimeout(t); return r.ok ? r.text() : Promise.reject(new Error('HTTP '+r.status)); })
                      .then(_ => {
                        // 服务端恢复：切回在线页（会自动连 WS）
                        if (retryDisplayUrl) location.href = retryDisplayUrl;
                      })
                      .catch(_ => {});
                  }
                  setInterval(probeOnce, 5000);
                  // 先立即探测一次
                  setTimeout(probeOnce, 800);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun mainFrameErrorHtml(failedUrl: String, description: String): String {
        val safeUrl = failedUrl.replace("<", "&lt;")
        val safeDesc = description.replace("<", "&lt;")
        return "<html><head><meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width\"/></head>" +
            "<body style=\"margin:0;background:#FF6633;color:#fff;font-family:sans-serif;padding:28px;line-height:1.55\">" +
            "<h2 style=\"margin-top:8px\">大屏页面打不开</h2>" +
            "<p>请确认电脑已运行 <code style=\"background:rgba(0,0,0,.25);padding:2px 6px;border-radius:4px\">node server.js</code>，" +
            "且本机与电脑在同一局域网、防火墙放行 TCP <strong>3000</strong> 与 UDP <strong>39300</strong>。</p>" +
            "<p><strong>失败地址</strong><br/><code style=\"word-break:break-all;background:rgba(0,0,0,.2);padding:6px;display:block\">$safeUrl</code></p>" +
            "<p><strong>原因</strong><br/>$safeDesc</p>" +
            "<p>可点左上角 ⚙ 填写 <code>http://电脑IP:3000/?mode=display</code> 并勾选「仅用上述地址」后返回。</p>" +
            "</body></html>"
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
        snapshotJob?.cancel()
        snapshotJob = null
        super.onPause()
    }

    private fun scheduleLoad() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val prefs = getSharedPreferences(PrefKeys.FILE, MODE_PRIVATE)
            val raw = resolveUrlString(prefs)
            val url = normalizeHttpUrl(raw)
            when {
                url == null -> {
                    // 没有可用 URL 时：优先离线快照；否则显示引导页
                    val offline = prefs.getString(PrefKeys.OFFLINE_TABLE_JSON, "")?.trim().orEmpty()
                    if (offline.isNotBlank()) {
                        val retryDisplayUrl = prefs.getString(PrefKeys.LAST_GOOD_URL, "")?.trim().orEmpty()
                        val retryApiUrl = buildApiTableStateUrl(retryDisplayUrl).orEmpty()
                        webView.loadDataWithBaseURL(
                            null,
                            offlineHtml(
                                tableJson = offline,
                                savedAtMs = prefs.getLong(PrefKeys.OFFLINE_SAVED_AT_MS, 0L),
                                failedUrl = "(未发现服务端地址)",
                                description = "未发现可用 URL，使用离线快照",
                                retryDisplayUrl = retryDisplayUrl,
                                retryApiUrl = retryApiUrl,
                            ),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    } else {
                        webView.loadDataWithBaseURL(
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
                    }
                }

                else -> {
                    Log.i(TAG, "WebView loadUrl: $url")
                    prefs.edit().putString(PrefKeys.LAST_GOOD_URL, url).apply()
                    webView.loadUrl(url)
                    startSnapshotLoop(prefs, url)
                }
            }
        }
    }

    private fun startSnapshotLoop(prefs: SharedPreferences, displayUrl: String) {
        snapshotJob?.cancel()
        snapshotJob = lifecycleScope.launch {
            val apiUrl = buildApiTableStateUrl(displayUrl)
            if (apiUrl == null) {
                Log.w(TAG, "离线快照抓取跳过：无法从 displayUrl 推导 /api/table-state")
                return@launch
            }

            // 等待服务端就绪，然后用 HTTP 直接抓 /api/table-state（不依赖网页 JS 变量作用域）
            delay(1200)
            repeat(60) { // 最多抓取约 2 分钟
                val normalized = fetchTableStateJson(apiUrl).trim()
                if (normalized.isNotBlank()) {
                    val ok = try {
                        val obj = JSONObject(normalized)
                        obj.optJSONArray("columns") != null && obj.optJSONArray("rows") != null
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        prefs.edit()
                            .putString(PrefKeys.OFFLINE_TABLE_JSON, normalized)
                            .putLong(PrefKeys.OFFLINE_SAVED_AT_MS, System.currentTimeMillis())
                            .apply()
                        Log.i(TAG, "离线快照已保存 url=$apiUrl bytes=${normalized.length}")
                        return@launch
                    }
                }
                delay(2000)
            }
            Log.w(TAG, "离线快照抓取超时（/api/table-state 未返回有效数据）")
        }
    }

    private fun buildApiTableStateUrl(displayUrl: String): String? {
        return try {
            val u = URI(displayUrl)
            if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) return null
            val port = if (u.port == -1) (if (u.scheme == "https") 443 else 80) else u.port
            "${u.scheme}://${u.host}:$port/api/table-state"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchTableStateJson(apiUrl: String): String =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(apiUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 1500
                    readTimeout = 1500
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    useCaches = false
                }
                conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
            } catch (_: Exception) {
                ""
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
