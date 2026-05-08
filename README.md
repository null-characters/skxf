# 数据看板（Excel → Web → Android 大屏）

本项目把 **Excel 看板文件**读取成 Web 页面（HTTP + WebSocket **同端口**），再由 Android 大屏 App（全屏 WebView）显示。

设计目标：

- **运维简单**：电脑端跑 `node server.js`，大屏端只需安装 APK。
- **不手输 IP**：大屏端通过 **UDP 发现**自动找到服务端显示页。
- **断网/停机可用**：无服务端时继续显示**最后一次成功的内容（离线快照）**，并在服务端恢复后**自动重连**。
- **可用 adb 全程远程运维**：安装/启动/截图/查日志都不需要触碰显示屏。

---

## 目录与关键文件

- **服务端**：[`server.js`](server.js)
- **前端单页**（显示/编辑）：[`public/index.html`](public/index.html)
- **Excel 数据**：`data/excel/研发项目看板2026.xlsx`
- **Android 大屏 App**：[`android/`](android/)
  - 入口：`android/app/src/main/java/com/skxf/display/MainActivity.kt`
  - UDP 发现：`android/app/src/main/java/com/skxf/display/DashboardDiscovery.kt`
  - 设置页（手动 URL）：`android/app/src/main/java/com/skxf/display/SettingsActivity.kt`

---

## 快速开始（推荐：Android 大屏 App）

### 1) 启动服务端（电脑）

在仓库根目录：

```bash
npm install
node server.js
```

启动成功后控制台会输出：

- 可访问的显示页（形如 `http://192.168.x.x:3000/?mode=display`）
- UDP 发现端口（默认 **39300**）
- `GET /api/table-state` 自检接口

常见启动问题：

- **端口被占用（EADDRINUSE）**：说明 `3000` 已被其它进程占用。
  - macOS：先 `./stop.sh`，或换端口启动：`PORT=3002 node server.js`（也可用脚本：`PORT=3002 ./start.sh`）
  - Windows：先 `stop.bat`，或换端口启动：`set PORT=3002 && node server.js`（也可用脚本：`start.bat 3002`）

也可以直接使用脚本启动/停止（会在启动前检查端口占用）：

- macOS：`./start.sh` / `./stop.sh`
- Windows：`start.bat` / `stop.bat`

### 2) 构建 APK（一次即可）并安装到显示屏

完整构建说明见 [`android/README.md`](android/README.md)。

Debug APK 默认产物路径：

- `android/app/build/outputs/apk/debug/app-debug.apk`

### 3) 用 adb 远程打开与调试（不操作显示屏）

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"

# 连接（无线调试示例）
"$ADB" connect 192.168.0.158:5555

# 安装并启动（-r 覆盖安装，保留设置）
"$ADB" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.skxf.display/.MainActivity

# 查看 App 当前加载了哪个 URL / 是否切离线
"$ADB" shell logcat -d -t 1200 | grep -E "SkxfDisplay: (WebView loadUrl|切换离线展示|离线快照已保存|WebSocket 已连接)" | tail -120

# 截图（远程确认效果）
"$ADB" exec-out screencap -p > ./screen.png
```

---

## 系统工作方式（务必理解）

### 端口与协议

- **HTTP + WebSocket**：同端口，默认 **3000**（见 [`server.js`](server.js) 的 `PORT`）。
- **UDP 发现**：默认 **39300**（环境变量 `DASHBOARD_DISCOVERY_PORT`）。
- **探测载荷**：UTF-8 字符串 **`SKXF_DISCOVER`**。
- **应答**：服务端向探测来源 **单播** JSON：
  - `schema`（当前为 `1`）
  - `httpPort`
  - `ips`（服务端可达 IPv4 列表）
  - `suggestUrl`（推荐显示页 URL）
  - `displayPath`（通常为 `/?mode=display`）

协议细节见：[`plan/architecture.md`](plan/architecture.md)。

### 谁在“广播”？

不是服务端广播，而是 **显示屏 App 主动广播探测**：

1. App 广播发送 `SKXF_DISCOVER`（UDP 39300）
2. 电脑端 `server.js` 收到后单播回复 JSON
3. App 选择 `suggestUrl`（校验失败则用 `ips + httpPort + displayPath` 拼接）
4. WebView 打开显示页并连接 WebSocket

### 为什么要过滤 `198.18.x.x`？

macOS 上常见代理/TUN 会生成 **`198.18.0.0/15`** 虚拟地址（局域网设备不可达）。  
本项目已在服务端过滤该网段与 `169.254.x.x`（链路本地），避免大屏拿到不可达地址而“连接超时”。

---

## 数据源（Excel）

服务端从 Excel 中读取表格并在文件变化时自动刷新：

- 默认文件：`data/excel/研发项目看板2026.xlsx`
- 配置位置：[`server.js`](server.js) 常量 `EXCEL_FILE`
- 读取逻辑：默认读取 **第 3 张表**（索引 2），期望表名为「全景计划」（不一致会有日志提示）

---

## Android 大屏 App（核心能力）

### 1) 全屏显示 + 设置页兜底

- 默认 **沉浸式全屏** WebView
- 左上角 ⚙ 可进入设置：
  - **手动 URL**（例如 `http://192.168.0.205:3000/?mode=display`）
  - “仅用上述地址”（跳过 UDP 发现）

### 2) 离线展示（最后一次成功内容）

在线时 App 会抓取 `GET /api/table-state` 并保存到本地（SharedPreferences）：

- `OFFLINE_TABLE_JSON`：上次成功的表格 JSON
- `OFFLINE_SAVED_AT_MS`：保存时间戳

当服务端不可达（超时/拒绝/HTTP 错误）时：

- 自动切换到 **离线快照页**继续展示内容
- 左下角状态显示：**`● 离线中（自动重连中…）`**

### 3) 自动重连（无需人工重启 App）

离线页会每 **5 秒**探测一次 `http://<上次host>:3000/api/table-state`：

- 一旦探测成功：自动跳回在线显示页 `http://<上次host>:3000/?mode=display`（并自动恢复 WebSocket）

---

## 常用地址与接口

- **显示页**：`http://<电脑IP>:3000/?mode=display`
- **编辑页（本机）**：`http://localhost:3000?mode=edit`
- **表格 JSON（自检/离线快照来源）**：`http://<电脑IP>:3000/api/table-state`

---

## 运维与排障（面向“无人值守大屏”）

### 现象：服务端打印 “数据已广播: 0 个 WebSocket 客户端在线”

- 表示当前 **没有浏览器/WebView 连接在 WebSocket 上**。
- 如果大屏处于离线快照页，它不会连 WS，因此服务端会是 0。
- 用 adb 看大屏真实状态（在线/离线/加载 URL）：

```bash
"$ADB" shell logcat -d -t 1200 | grep -E "SkxfDisplay: (WebView loadUrl|切换离线展示|WebSocket 已连接)" | tail -120
```

### 现象：大屏一直离线

优先检查：

- 电脑端 `node server.js` 是否在跑（端口 3000 是否监听）
- 显示屏与电脑是否在同一网段、是否有访客隔离/VLAN
- 防火墙是否放行：
  - TCP 3000（页面 + WebSocket）
  - UDP 39300（发现应答）
- 兜底：用设置页写死 URL（或用 adb 打开设置页）

---

## 环境与配置

### 必需

| 依赖 | 说明 |
|------|------|
| Node.js | 服务端运行时 |

### 可选（构建 APK）

见 [`android/README.md`](android/README.md)：JDK 17、Android SDK 34、Gradle 8.4、以及 `adb` 路径。

### 常用环境变量（服务端）

更多安全细节见：[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md)

- `PORT`：HTTP/WS 端口（默认 3000）
- `HOST`：监听地址（默认 `0.0.0.0`）
- `DASHBOARD_DISCOVERY_PORT`：UDP 发现端口（默认 39300；设为 `0` 关闭）
- `DASHBOARD_EDIT_TOKEN`：远程编辑令牌（不设置时仅本机回环可写）
- `DASHBOARD_ALLOW_INSECURE_LAN_EDITS`：允许局域网任意客户端编辑（不推荐）
- `DASHBOARD_CORS_ORIGIN`：跨域访问 `/api/table-state` 的允许 Origin

---

## 文档索引

- Android 构建/安装/adb：[`android/README.md`](android/README.md)
- 方案与协议（UDP/字段/数据流）：[`plan/architecture.md`](plan/architecture.md)
- Android 细节（权限/明文 HTTP/发现）：[`plan/android.md`](plan/android.md)
- 安全与边界：[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md)

---

## License

MIT
