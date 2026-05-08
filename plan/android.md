# Android 端实现指南

面向 **信息发布屏**：单 Activity + **WebView 全屏**加载大屏 URL；启动时用 **UDP 发现**解析服务地址，避免手写 DHCP 变化的 IP。

下文假设使用 **Kotlin**、**AndroidX**、**minSdk 21**（可按设备上调）。独立模块或新建「Empty Activity」工程均可。

## 1. 依赖与 Gradle

在模块 `build.gradle.kts` 中保证至少：

```kotlin
android {
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
```

无需为发现逻辑额外引入三方库；解析 JSON 可使用 **`org.json.JSONObject`**（Android SDK 自带）。

## 2. 权限（AndroidManifest.xml）

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

说明：

- **INTERNET**：WebView 访问 `http://` 大屏。
- **MulticastLock 相关**：部分机型在 **WiFi** 上对广播/组播较保守，发现前持有 **`WifiManager.MulticastLock`** 可提高 UDP 收包成功率。

## 3. 明文 HTTP（局域网）

Android 9（API 28）起默认禁止明文流量。仅在内网大屏场景可为应用放宽限制。

**方式 A（简单，适合专用发布屏 APK）**：在 `<application>` 上增加：

```xml
android:usesCleartextTraffic="true"
```

**方式 B（略规范）**：使用 `network_security_config.xml` 仅允许内网段 cleartext（可按环境细化）。

## 4. 发现协议常量（与服务端一致）

| 常量 | 值 |
|------|-----|
| 载荷 | `SKXF_DISCOVER`（UTF-8） |
| UDP 端口 | 默认 **39300**，与服务端 `DASHBOARD_DISCOVERY_PORT` 一致 |
| 广播地址 | `255.255.255.255` |

服务端应答 JSON 字段见 [architecture.md](./architecture.md)。

## 5. 发现逻辑示例（Kotlin）

以下示例在 **后台线程** 执行阻塞式 `DatagramSocket.receive`，超时后返回 `null`。成功则返回 **`suggestUrl`**，若为空则尝试用 **`ips` + `httpPort` + `displayPath`** 拼出第一条 URL。

```kotlin
import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

object DashboardDiscovery {

    private const val PAYLOAD = "SKXF_DISCOVER"
    private const val UDP_PORT = 39300
    private const val BROADCAST = "255.255.255.255"
    private const val RECEIVE_TIMEOUT_MS = 2500

    /**
     * @return 大屏完整 URL，失败返回 null
     */
    fun discoverDisplayUrl(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("skxf-dashboard-discover")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val sendBytes = PAYLOAD.toByteArray(StandardCharsets.UTF_8)
                val sendPacket = DatagramPacket(
                    sendBytes,
                    sendBytes.size,
                    InetAddress.getByName(BROADCAST),
                    UDP_PORT,
                )
                socket.send(sendPacket)

                val buf = ByteArray(8192)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val jsonText = String(recv.data, recv.offset, recv.length, StandardCharsets.UTF_8).trim()
                val json = JSONObject(jsonText)

                val suggest = json.optString("suggestUrl", "").trim()
                if (suggest.isNotEmpty()) return suggest

                val port = json.optInt("httpPort", 0)
                val path = json.optString("displayPath", "/?mode=display").trim().ifEmpty { "/?mode=display" }
                val ips = json.optJSONArray("ips") ?: return null
                if (port <= 0 || ips.length() == 0) return null
                val ip = ips.getString(0).trim()
                if (ip.isEmpty()) return null
                return "http://$ip:$port$path"
            }
        } catch (_: Exception) {
            return null
        } finally {
            lock.release()
        }
    }
}
```

可选增强：

- 解析 JSON 后若 **`suggestUrl` 打不开**，依次 **`ips`** 轮换尝试（需配合 WebView `WebViewClient.onReceivedError` 或先发 `HEAD`/`GET` 探测）。
- 将最后一次成功的 URL 写入 **`SharedPreferences`**，发现失败时作为 fallback。

## 6. WebView Activity 要点

- **进程启动**：在 `onCreate` 中对 **非主线程** 调用 `DashboardDiscovery.discoverDisplayUrl()`，结束后在主线程 **`webView.loadUrl(url)`**。
- **配置**：

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
    webView.settings.mixedContentMode =
        android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
}
```

- **全屏**：使用 `WindowInsetsController` 隐藏状态栏/导航栏（API 30+），或 `SYSTEM_UI_FLAG_*`（旧设备）；电视可考虑 **Leanback** 主题。
- **返回键**：发布屏常禁用返回退出，或只在调试菜单开放。
- **生命周期**：若页面需常驻后台，注意省电策略；大屏前端已有 WebSocket 重连与 HTTP 轮询，一般无需在原生侧额外保活。

## 7. 与纯浏览器方案对比

| 方式 | 动态 IP | 触屏操作 |
|------|---------|----------|
| 电视浏览器手动输入 URL | 依赖发现以外的手段记 IP | 每次打开浏览器较繁琐 |
| 本方案 WebView + UDP | 启动自动发现（同网段） | 桌面单一图标，开箱即显示 |

## 8. 调试建议

1. PC 上运行 `node server.js`，确认控制台出现 **「局域网发现: UDP 39300 …」**。
2. 用手机 / 模拟器与 PC **同一 WiFi**，安装 APK 后看 Logcat 是否在超时内有 UDP 回复。
3. 若永远超时：检查 **防火墙** 是否放行 UDP **39300**、路由器是否 **AP 隔离**，以及 PC 是否多块网卡导致回复 IP 不可达（可依赖客户端对 **`ips`** 回退尝试）。

---

以上实现与仓库内 **`server.js` 中 `startDiscoveryUdp`、`public/index.html` 显示模式** 对齐；服务端端口或载荷变更时，请同步修改本文常量与 [architecture.md](./architecture.md)。
