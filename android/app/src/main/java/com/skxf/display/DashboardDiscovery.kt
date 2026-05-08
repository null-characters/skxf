package com.skxf.display

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * 与 [plan/architecture.md]、[plan/android.md] 一致：
 * 向 `255.255.255.255:39300` 发送 UTF-8 `SKXF_DISCOVER`，等待服务端 **单播** JSON 应答。
 *
 * `DASHBOARD_DISCOVERY_PORT` 未设置时服务端默认 **39300**（若修改端口，此处需与服务端保持一致）。
 */
private const val UDP_PORT = 39300
private const val BROADCAST_HOST = "255.255.255.255"
private val PAYLOAD_BYTES = "SKXF_DISCOVER".toByteArray(StandardCharsets.UTF_8)

private fun isSafeDisplayPath(path: String): Boolean {
    if (!path.startsWith("/")) return false
    if (path.contains("..")) return false
    if (path.length > 512) return false
    return true
}

private fun jsonGetIps(json: JSONObject): List<String> {
    val arr = json.optJSONArray("ips") ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.optString(i, "").trim()
        if (v.isNotEmpty()) out.add(v)
    }
    return out
}

/**
 * 最小化校验 `suggestUrl`：
 * - 必须是 http
 * - host 必须在 ips 列表中
 * - port 必须等于 httpPort
 * - 末尾路径必须匹配 displayPath（防止带其它 path）
 */
private fun validateSuggestUrl(
    suggestUrl: String,
    ips: List<String>,
    httpPort: Int,
    displayPath: String,
): String? {
    val u = suggestUrl.trim()
    if (!u.startsWith("http://")) return null
    if (!u.endsWith(displayPath)) return null

    val hostCandidate = run {
        val noProto = u.removePrefix("http://")
        val slashIdx = noProto.indexOf('/')
        val hostPort = if (slashIdx >= 0) noProto.substring(0, slashIdx) else noProto
        val parts = hostPort.split(':')
        if (parts.size < 2) return null
        parts[0]
    } ?: return null

    if (hostCandidate !in ips) return null

    // 校验端口
    val portCandidate = run {
        val noProto = u.removePrefix("http://")
        val slashIdx = noProto.indexOf('/')
        val hostPort = if (slashIdx >= 0) noProto.substring(0, slashIdx) else noProto
        val parts = hostPort.split(':')
        if (parts.size < 2) return null
        parts[1].toIntOrNull()
    }
    if (portCandidate == null || portCandidate != httpPort) return null

    return u
}

/**
 * @return `suggestUrl` 或可拼接的第一个 `ips` URL；失败返回 `null`
 */
suspend fun discoverDashboardUrl(context: Context, receiveTimeoutMs: Int = 2600): String? =
    withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("skxf-dashboard-discover")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = receiveTimeoutMs
                val sendPacket = DatagramPacket(
                    PAYLOAD_BYTES,
                    PAYLOAD_BYTES.size,
                    InetAddress.getByName(BROADCAST_HOST),
                    UDP_PORT,
                )
                socket.send(sendPacket)

                val buf = ByteArray(8192)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val jsonText =
                    String(recv.data, recv.offset, recv.length, StandardCharsets.UTF_8).trim()
                val json = JSONObject(jsonText)

                // 协议最小校验：必须来自我们约定的 schema
                val schema = json.optInt("schema", -1)
                if (schema != 1) return@withContext null

                val httpPort = json.optInt("httpPort", 0)
                if (httpPort !in 1..65535) return@withContext null

                val displayPath = json.optString("displayPath", "/?mode=display").trim()
                    .ifEmpty { "/?mode=display" }
                if (!isSafeDisplayPath(displayPath)) return@withContext null

                val ips = jsonGetIps(json)
                if (ips.isEmpty()) return@withContext null

                // 1) 如果 suggestUrl 存在，就用 ips/httpPort/displayPath 做自洽校验
                val suggestUrl = json.optString("suggestUrl", "").trim()
                if (suggestUrl.isNotEmpty()) {
                    val validated = validateSuggestUrl(
                        suggestUrl = suggestUrl,
                        ips = ips,
                        httpPort = httpPort,
                        displayPath = displayPath,
                    )
                    if (validated != null) return@withContext validated
                }

                // 2) 回退：按 ips 顺序尝试拼 URL（不做可达性探测，交给 WebView 最终结果）
                for (ip in ips) {
                    if (ip.isNotBlank()) {
                        return@withContext "http://$ip:$httpPort$displayPath"
                    }
                }

                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                lock.release()
            } catch (_: RuntimeException) {
                // already released / not held on some OEM
            }
        }
    }
