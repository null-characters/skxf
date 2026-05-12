package com.skxf.display

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * UDP 发现服务端
 * - 向 255.255.255.255:39300 发送 SKXF_DISCOVER
 * - 等待服务端单播 JSON 应答
 */
private const val UDP_PORT = 39300
private const val BROADCAST_HOST = "255.255.255.255"
private val PAYLOAD_BYTES = "SKXF_DISCOVER".toByteArray(StandardCharsets.UTF_8)

private const val TAG = "SkxfDiscovery"

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

private fun validateSuggestUrl(
    suggestUrl: String,
    ips: List<String>,
    httpPort: Int,
    displayPath: String,
): String? {
    val u = suggestUrl.trim()
    if (!u.startsWith("http://")) return null
    if (!u.endsWith(displayPath)) return null

    // 解析 host:port
    val noProto = u.removePrefix("http://")
    val slashIdx = noProto.indexOf('/')
    val hostPort = if (slashIdx >= 0) noProto.substring(0, slashIdx) else noProto
    val parts = hostPort.split(':')
    if (parts.size < 2) return null
    
    val hostCandidate = parts[0]
    val portCandidate = parts[1].toIntOrNull()
    
    if (hostCandidate !in ips) return null
    if (portCandidate == null || portCandidate != httpPort) return null

    return u
}

/**
 * 发现结果数据类
 */
data class DiscoveryResult(
    val url: String,
    val httpPort: Int,
    val ips: List<String>,
    val rawJson: JSONObject
)

/**
 * 单次 UDP 发现
 * @return 发现成功返回 DiscoveryResult，失败返回 null
 */
suspend fun discoverDashboardUrl(context: Context, receiveTimeoutMs: Int = 2600): DiscoveryResult? =
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
                Log.d(TAG, "已发送发现请求到 $BROADCAST_HOST:$UDP_PORT")

                val buf = ByteArray(8192)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val jsonText =
                    String(recv.data, recv.offset, recv.length, StandardCharsets.UTF_8).trim()
                Log.d(TAG, "收到响应来自 ${recv.address}:${recv.port}: $jsonText")
                
                val json = JSONObject(jsonText)

                val schema = json.optInt("schema", -1)
                if (schema != 1) {
                    Log.w(TAG, "schema 不匹配: $schema")
                    return@withContext null
                }

                val httpPort = json.optInt("httpPort", 0)
                if (httpPort !in 1..65535) {
                    Log.w(TAG, "httpPort 无效: $httpPort")
                    return@withContext null
                }

                val displayPath = json.optString("displayPath", "/?mode=display").trim()
                    .ifEmpty { "/?mode=display" }
                if (!isSafeDisplayPath(displayPath)) {
                    Log.w(TAG, "displayPath 不安全: $displayPath")
                    return@withContext null
                }

                val ips = jsonGetIps(json)
                if (ips.isEmpty()) {
                    Log.w(TAG, "ips 为空")
                    return@withContext null
                }

                // 优先使用 suggestUrl
                val suggestUrl = json.optString("suggestUrl", "").trim()
                if (suggestUrl.isNotEmpty()) {
                    val validated = validateSuggestUrl(
                        suggestUrl = suggestUrl,
                        ips = ips,
                        httpPort = httpPort,
                        displayPath = displayPath,
                    )
                    if (validated != null) {
                        Log.i(TAG, "发现成功(建议URL): $validated")
                        return@withContext DiscoveryResult(validated, httpPort, ips, json)
                    }
                }

                // 回退：使用第一个 IP
                for (ip in ips) {
                    if (ip.isNotBlank()) {
                        val url = "http://$ip:$httpPort$displayPath"
                        Log.i(TAG, "发现成功(拼装URL): $url")
                        return@withContext DiscoveryResult(url, httpPort, ips, json)
                    }
                }

                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "发现失败: ${e.message}")
            null
        } finally {
            try {
                lock.release()
            } catch (_: RuntimeException) { }
        }
    }
