# 数据看板（Excel → Web → Android 大屏）

把 Excel 看板文件读取成网页（HTTP + WebSocket，同端口），再用 Android 大屏 App **全屏显示**。大屏侧支持 **UDP 自动发现**（避免手输 IP），也支持在设置里写死 URL。

---

## 快速开始（推荐：Android 大屏 App）

### 1) 启动服务端（电脑）

在仓库根目录：

```bash
npm install
node server.js
```

成功后控制台会输出可访问地址（形如 `http://192.168.x.x:3000/?mode=display`），并提示 UDP 发现端口。

### 2) 构建并安装 Android APK（一次即可）

详见 **[`android/README.md`](android/README.md)**（含 JDK/SDK/Gradle、命令行构建、`adb`）。

APK 默认产物路径：

- `android/app/build/outputs/apk/debug/app-debug.apk`

### 3) 全程用 ADB 调试与打开（不操作显示屏）

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"

# 连接（无线调试）
"$ADB" connect 192.168.0.158:5555

# 安装并启动
"$ADB" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.skxf.display/.MainActivity

# 看当前 WebView 试图加载的 URL / 报错
"$ADB" shell logcat -d -t 400 | grep -E "SkxfDisplay|WebView loadUrl|主文档" | tail -120

# 截图（用于远程确认效果）
"$ADB" exec-out screencap -p > ./screen.png
```

---

## 当前方案（实现要点）

- **端口**：HTTP + WebSocket 共用 **3000**（默认），避免大屏端只放行一个端口时 WS 连不上。
- **数据源**：服务端从仓库内 Excel 读取并监听改动。
  - 当前默认路径：`data/excel/研发项目看板2026.xlsx`
  - 配置位置：[`server.js`](server.js) 的 `EXCEL_FILE`
- **UDP 发现（Android → Server）**：
  - 大屏广播 `SKXF_DISCOVER` 到 `255.255.255.255:39300`
  - 服务端单播回 JSON（`schema/httpPort/ips/suggestUrl/displayPath`）
  - 端口开关：环境变量 `DASHBOARD_DISCOVERY_PORT`（默认 39300；设为 `0` 关闭）
- **可达性优化（关键）**：服务端会过滤如 **`198.18.x.x`**（Clash/TUN 假网段）与 `169.254.x.x`，避免大屏拿到不可达地址导致“连接超时”。
- **大屏 App（WebView）**：
  - 全屏沉浸式
  - UDP 自动发现失败时可在 ⚙ 设置 **手动 URL**，并可勾选“仅手动地址”
  - 主文档加载失败会显示橙色错误页（含失败 URL 与原因），便于 `adb` 远程排查

---

## 常用地址与接口

- **显示页**：`http://<电脑IP>:3000/?mode=display`
- **编辑页（本机）**：`http://localhost:3000?mode=edit`
- **表格 JSON（自检）**：`http://localhost:3000/api/table-state`

---

## 环境要求

| 依赖 | 说明 |
|------|------|
| **Node.js** | 服务端运行时 |
| **Android 构建链（可选）** | 仅构建 APK 时需要，见 [`android/README.md`](android/README.md) |

---

## 文档索引

- **Android 大屏 App（构建/ADB/手动 URL）**：[`android/README.md`](android/README.md)
- **UDP 发现协议与字段**：[`plan/architecture.md`](plan/architecture.md)
- **Android 发现细节与权限**：[`plan/android.md`](plan/android.md)
- **安全与环境变量**：[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md)

---

## License

MIT
