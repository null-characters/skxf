# 整体架构

## 目标

- **编辑端**：在运行 Node 服务的电脑上使用浏览器打开 `?mode=edit`（可配合 `DASHBOARD_EDIT_TOKEN`）。
- **发布屏端**：Android（电视或平板）以 **全屏 WebView** 打开 `?mode=display`，行为与 Chrome 打开同一 URL 一致。
- **不要求服务端静态 IP**：通过 **UDP 局域网发现** 在 DHCP 变更后仍能解析出当前可达的 `http://地址:端口`。

## 服务端（Node）

- **进程**：`node server.js`（见根目录 `package.json` 的 `start`）。
- **HTTP**：默认端口 `PORT`（环境变量，默认 **3000**），监听 `HOST`（默认 `0.0.0.0`）。
- **静态页与大屏**：`GET /` → `public/index.html`；大屏使用查询参数 **`?mode=display`**。
- **初始数据 / 轮询兜底**：`GET /api/table-state` 返回当前表格 JSON。大屏脚本会先拉取该接口再连 WebSocket，且显示模式下 **约每 8 秒** 再次 HTTP 拉取，减轻「大屏 WebSocket 连不上导致空白」问题。
- **实时推送**：与 HTTP **同一端口** 上的 WebSocket（`ws` 库）。连接建立后服务端下发 `init`，此后表格变更广播 `update`。大屏端脚本使用 **`ws://当前页面的 host`**（即与浏览器同源），无需单独端口。

## UDP 局域网发现（动态 IP）

适用于：**不想给服务器配置静态 IP**，但发布屏与服务端在同一二层局域网（家庭 / 办公同一网段；访客 WiFi 或隔离 VLAN 若禁止广播则可能不可用）。

| 项 | 说明 |
|----|------|
| 传输 | **UDP IPv4** |
| 监听地址 | `0.0.0.0` |
| 端口 | **`DASHBOARD_DISCOVERY_PORT`**，未设置时默认为 **39300**；设为 **`0`** 或非法值则关闭发现 |
| 客户端载荷 | UTF-8 字符串 **`SKXF_DISCOVER`**（前后空白会被忽略） |
| 发送方式 | 建议发往 **`255.255.255.255:端口`**（广播）；亦可向已知单播地址发送同一载荷 |
| 应答 | 服务端向探测来源 **单播** 回复 UTF-8 JSON |

应答 JSON 字段（`schema: 1`）：

| 字段 | 含义 |
|------|------|
| `httpPort` | 当前 HTTP/WebSocket 端口（与 `PORT` 一致） |
| `ips` | 本机当前所有非回环 **IPv4** 列表（每次应答前重新枚举，随 DHCP 更新） |
| `suggestUrl` | 推荐的完整显示页 URL，一般形如 `http://<优选IP>:<端口>/?mode=display`。服务端会优先选择与 **探测客户端同一 /24 网段** 的地址，减轻多网卡（VPN、虚拟网卡）下列表顺序不佳的问题 |
| `displayPath` | 固定为 `/?mode=display`，便于客户端自行拼接 |

客户端逻辑建议：

1. 优先加载 **`suggestUrl`**。
2. 若 WebView 加载失败（超时或 HTTP 错误），可依次尝试 **`ips`** 中每个地址拼接 `http://ip:httpPort/displayPath`。
3. 成功后可 **缓存最后一次可用 URL**（如 `SharedPreferences`），下次启动先短暂探测，失败则用缓存加速恢复。

## 环境变量（与方案相关）

| 变量 | 作用 |
|------|------|
| `PORT` | HTTP/WebSocket 端口，默认 3000 |
| `HOST` | HTTP 绑定地址，默认 `0.0.0.0` |
| `DASHBOARD_DISCOVERY_PORT` | UDP 发现端口，默认 39300；`0` 关闭 |
| `DASHBOARD_EDIT_TOKEN` | 非本机编辑时 WebSocket `auth` 使用的令牌（与 `?mode=edit&token=` 配合） |
| `DASHBOARD_ALLOW_INSECURE_LAN_EDITS` | 设为 `1`/`true`/`yes` 时允许任意局域网客户端写表（不推荐生产） |

## 安全边界（简要）

- 默认情况下，**仅本机回环** WebSocket 连接具备写权限；大屏多为只读。
- 大屏使用 **明文 HTTP** 时仅适用于可信局域网；若跨公网需自行改为 HTTPS/WSS 并处理证书与 WebView 策略。
- UDP 发现会在局域网内暴露「存在看板服务」及当前候选 IP，请在可信网络中使用。

## 方案选型小结

| 做法 | 是否推荐 |
|------|----------|
| 发布屏：Android WebView 固定打开 `/?mode=display` | **推荐**，与现有前端完全一致 |
| 发布屏：重写原生 UI 只调 `/api/table-state` | 不推荐，重复实现样式与逻辑 |
| 动态 IP：UDP `SKXF_DISCOVER` | **推荐**，已实现于 `server.js` |
| 动态 IP：路由器 DHCP 预留 + 静态 IP | 可选，用户明确表示可不采用 |
