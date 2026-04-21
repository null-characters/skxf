# 数据看板 - Web 表格投屏应用

一个轻量级的 Web 表格编辑与实时投屏应用，专为信息发布屏设计。

## 功能特点

- **双模式切换**：编辑模式 + 显示模式
- **实时同步**：WebSocket 与 HTTP **共用 3000 端口**，编辑端修改后推送到显示端（避免电视端防火墙只放行单端口时 WS 连不上）
- **HTTP 拉取兜底**：提供 `GET /api/table-state` 返回当前表格 JSON；显示端会首屏拉取并定时刷新，**即使 WebSocket 为 0 在线也能看到 Excel 数据**
- **零配置**：单文件服务器，无需复杂框架
- **适配大屏**：显示模式针对 1920x1080 横屏优化

---

## 理解范畴（部署与网络模型）

本节帮助建立正确心智模型，避免「广播」「必须写死某个 IP」等误解。

### 服务在电脑上做了什么？

启动 **`start.bat` / `node server.js`** 后，程序在本机 **TCP 3000 端口** 上 **监听**（绑定 `0.0.0.0:3000`），相当于在这台电脑上跑了一个很小的 **网站 + 接口**：

- 提供网页（如 `/?mode=display`、`/?mode=edit`）；
- 提供 **`/api/table-state`** 等 HTTP 接口；
- 在同一端口上提供 **WebSocket**（与 HTTP 共用 3000，避免电视端只放行一个端口时连不上 WS）。

**不是** 向整个局域网发 **UDP 广播**；也不会让「没打开浏览器的设备」自动收到数据。

### 电视 / 其他设备怎样拿到数据？

只有 **主动** 用浏览器（或等价客户端）去访问 **`http://<运行服务的电脑 IPv4>:3000/...`** 时，才会与这台电脑 **建立连接**，从而加载页面、拉取 JSON 或建立 WebSocket。

- **HTTP / 轮询**：谁请求 **`/api/table-state`**，谁拿到当前内存里的表格快照。
- **WebSocket**：**已经打开看板页并保持连接** 的客户端，才能收到服务端 **推送** 的编辑更新；仍是「连上的那些终端」，不是全网自动投递。

直观说法可以是「在本机 3000 端口提供服务，局域网里能访问该 IP 的设备都可以连上来取数」；严谨说法是 **TCP 监听 + 客户端主动连接**，而非无线电式广播。

### 是否必须在项目里配置 IP？

**不必须。** `config.json` 里的 **`displayHost`** 只影响：启动时的文字提示、编辑页底部提示、ADB 一键打开电视时的 URL。**不配也能用**：启动后看 **`ipconfig`** 或控制台打印的 IPv4，在电视上手动输入 **`http://该IP:3000/?mode=display`** 即可。

需要省事时，可运行 **`sync-display-host.bat`** 把当前机 IPv4 写入 `config.json`，或给本机设 **静态 IP**，减少 DHCP 变更后电视书签失效。

### 电视上的地址到底是谁的 IP？

地址必须是 **正在运行 Node 服务的那台 Windows 电脑的局域网 IPv4**，不能填 **电视自己的 IP**。换电脑后，新机往往是 **新 IP**；要么给新机配置与原来相同的静态 IP（电视书签可不改），要么改电视上的 **`http://新IP:3000/?mode=display`**。

### 多台电脑同时存在时

每一台运行本项目的电脑，都是 **独立服务**，各自占用 **本机的 3000 端口**，对外分别是 **`http://电脑A的IP:3000`**、**`http://电脑B的IP:3000`** …

一块大屏、一个浏览器标签通常 **同一时间只显示其中一个** 地址的内容；要看另一台电脑上的看板，就改成 **另一台电脑的 IP**（或换书签）。

### 「同一网络里都能访问吗？」

**大致如此，但有前提：** 设备与服务器之间 **IP 可达**，且 **Windows 防火墙 / 路由器访客隔离 / 不同网段 VLAN** 等未阻止访问 **目标机的 TCP 3000**。若 ping 通但网页打不开，优先检查防火墙与是否用了 **http**（不要用错 **https**）。

---

## 环境要求

在运行本应用之前，请确保已安装以下环境：

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| **Node.js** | >= 14.0 | 运行时环境，必须安装 |

> 本项目不包含 `node_modules` 目录，首次运行需要联网安装依赖包（`ws`、`xlsx`），启动脚本会自动完成安装。

---

## Windows 使用教程

### 第一步：安装 Node.js

1. 访问官网：https://nodejs.org/
2. 下载 **LTS**（长期支持）版本
3. 运行安装程序，**勾选 "Add to PATH"**，一路下一步完成安装
4. 安装完成后，打开 cmd 输入 `node -v`，能显示版本号即安装成功

### 第二步：解压项目

将 `skxf.zip` 解压到任意目录（如 `C:\skxf`）

### 第三步：启动服务

1. 打开解压后的文件夹
2. 在文件夹地址栏输入 `cmd` 回车，打开命令行
3. 执行以下命令：

```cmd
npm install
node server.js
```

或直接双击 `start.bat` 一键启动（首次运行会自动安装依赖）。

启动成功后，控制台会打印 **`表格 JSON（大屏拉数）: http://localhost:3000/api/table-state`**。若升级了 `server.js` 却看不到该行或接口返回 `Not Found`，请先 **`stop.bat`** 再 **`start.bat`**，确保 3000 端口上是新进程。

### 第四步：固定局域网 IP（强烈推荐）

信息发布屏、平板等设备上要长期填写同一个地址，**请给运行本服务的电脑在路由器所在网段设置「静态 IP」**（或在路由器里做 DHCP 固定分配），例如固定为 `192.168.0.205`。  
Windows 示例：**设置 → 网络和 Internet → WLAN → 硬件属性 / 编辑 IP 分配 → 手动**，填入与路由器同网段的 IP、子网掩码、网关与 DNS。

### 第五步：配置 `config.json`

复制 `config.example.json` 为 `config.json`（`config.json` 已加入 `.gitignore`，不会提交到 Git）。

| 字段 | 含义 | 示例 |
|------|------|------|
| **`displayHost`** | **运行本服务的电脑** 在局域网中的 IP（电视浏览器要连它） | `192.168.0.188` |
| **`adbDevice`** | **Android 显示设备** 的 adb 地址（仅用于 `configure-android-display` 脚本） | `192.168.0.158:5555` |

注意：**不要把 `displayHost` 写成电视的 IP（如 158）**，否则页面会去访问电视本机的 3000 端口，而不是电脑上的 Node 服务。

改完后重启 `node server.js`。若未创建 `config.json`，启动时仍会列出当前网卡检测到的 IPv4。

**是否必须配置 IP？** **不是。** 服务监听 `0.0.0.0:3000`，不填 `config.json` 也能运行。你只要 **`start.bat` 启动后**，在电视上用浏览器打开 **`http://<本机 IPv4>:3000/?mode=display`** 即可（IPv4 在 `cmd` 里执行 **`ipconfig`** 查看，或看启动窗口里打印的地址列表）。

若希望 **自动把当前电脑的 IPv4 写入 `config.json`**（方便编辑页提示、ADB 一键开电视），可双击 **`sync-display-host.bat`**，再启动服务。

### 第六步：访问应用

启动成功后，在浏览器访问：

| 模式 | 地址 | 说明 |
|------|------|------|
| 编辑模式 | `http://localhost:3000?mode=edit` | 本地编辑表格 |
| 显示模式 | `http://<displayHost 或本机IP>:3000?mode=display` | 信息发布屏显示 |

> 推荐始终使用 **`config.json` 中的 `displayHost`**，与电脑静态 IP 保持一致。

### 让信息发布屏访问

1. **本机 IP**：与 `config.json` 的 `displayHost` 一致（静态 IP）
2. **开放防火墙**：首次启动时 Windows 防火墙会弹窗，选择 **"允许访问"**
3. **设备访问**：在信息发布屏浏览器输入 `http://<displayHost>:3000?mode=display`

### 用 ADB 一键打开显示端（可选）

电脑已安装 **Platform Tools（adb）** 且与电视在同一局域网时，可在项目目录执行：

```cmd
configure-android-display.bat
```

脚本会读取 `config.json` 里的 **`adbDevice`**（默认示例为 `192.168.0.158:5555`）、`adb connect`、结束 Chrome，并打开  
`http://<displayHost>:3000/?mode=display`。

临时指定别的 adb 地址（覆盖配置）：

```cmd
configure-android-display.bat -Device 192.168.0.158:5555
```

若电视端 **画面一直不变**、或曾用错误脚本打开过页面：Chrome 里可能堆了多个标签（含 `192.168.0.205`、或 URL 被拼进 `-n` 的坏地址）。请用 **清除 Chrome 数据** 后只开一页（会清空该设备 Chrome 的登录与书签）：

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -File configure-android-display.ps1 -ResetChrome
```

---

## 同步机制与接口（更新进度摘要）

| 能力 | 说明 |
|------|------|
| **WebSocket** | 与页面同源（`ws://<页面 host>/`），编辑与显示实时一致；日志中 **`数据已广播: N 个`** 的 `N` 为当前在线客户端数。 |
| **`GET /api/table-state`** | 返回服务端内存中的完整表格（与 Excel 解析结果一致）；浏览器可直接打开该地址检查是否有 JSON。 |
| **显示端轮询** | 显示模式下约每 **8 秒** 请求一次 `/api/table-state`，Excel 变更或 WS 异常时大屏仍能逐步更新。 |
| **编辑端** | 仅首屏通过 HTTP 拉取；编辑仍以 WebSocket 为主，避免轮询覆盖正在修改的单元格。 |

---

## 故障排除（常见问题）

1. **电视能 ping 通电脑，但网页一直加载、表体空白**  
   - 确认地址是 **`http://<电脑IP>:3000`**（不要用 `https://`）。  
   - **`displayHost` 必须是运行 Node 的电脑 IP**，不能填电视 IP。  
   - 本机打开 `http://<电脑IP>:3000/api/table-state` 应看到 JSON；若 **`Not Found`**：先 **`stop.bat`** 再 **`start.bat`**。  
   - 若装了 **Clash 等代理**，请对 **局域网 `192.168.x.x` 使用直连**，避免劫持 HTTP/WS。

2. **`数据已广播: 0 个 WebSocket 客户端`**  
   - 表示当时没有浏览器连上 WS；显示端仍可依赖 **`/api/table-state`** 与定时拉取显示数据。  
   - 可检查电视 Chrome 是否省电限制、多标签卡住；必要时 **`configure-android-display.ps1 -ResetChrome`**。

3. **ADB 打开 Chrome 后地址异常（URL 带 `%20-n` 等）**  
   - 请使用项目内的 **`configure-android-display.bat`**（`am start` 参数已按正确方式传递），勿把整条命令包在一层错误的引号里。

4. **端口被占用（`EADDRINUSE`）**  
   - 运行 **`stop.bat`** 或结束占用 3000 端口的 Node 进程后再启动。

---

## macOS 使用教程

### 第一步：安装 Node.js

```bash
brew install node
```

或从 https://nodejs.org/ 下载安装包

安装完成后，终端输入 `node -v` 确认安装成功。

### 第二步：启动服务

```bash
cd /path/to/skxf
npm install
node server.js
```

或运行 `./start.sh` 一键启动（首次运行会自动安装依赖）。

---

## 项目结构

```
skxf/
├── server.js           # Node.js 服务器（HTTP + WebSocket）
├── public/
│   └── index.html      # 单页面应用
├── data/               # Excel 数据文件目录
├── config.example.json # 复制为 config.json，填写固定 displayHost
├── package.json        # npm 配置及依赖声明
├── start.bat           # Windows 一键启动脚本
├── configure-android-display.bat / .ps1  # ADB 打开显示页；支持 -ResetChrome
├── sync-display-host.bat / .ps1          # 可选：将本机 IPv4 写入 config.json 的 displayHost
├── start.sh            # macOS/Linux 一键启动脚本
├── stop.bat            # Windows 停止服务脚本
├── stop.sh             # macOS/Linux 停止服务脚本
└── README.md           # 本文件
```

> **注意**：`node_modules` 目录未包含在分发包中，首次启动时需通过 `npm install` 安装。

---

## 使用说明

### 编辑模式

1. 在本地浏览器打开 `http://localhost:3000?mode=edit`
2. 直接点击单元格编辑内容
3. 点击行可选中，使用工具栏添加/删除行
4. 所有修改自动同步到显示端

### 显示模式

1. 在信息发布屏打开 `http://<服务器IP>:3000?mode=display`
2. 页面全屏显示表格数据（首屏及约每 8 秒通过 **`/api/table-state`** 与内存同步；WebSocket 在线时编辑可近乎实时更新）
3. 深色主题，大字体，适合远距离观看
4. 自动接收编辑端推送的数据更新（WebSocket）

## 数据来源

应用启动时自动读取 `data/excel/` 目录下的 Excel 文件作为数据源：

- **数据文件**：`data/excel/研发项目看板2026.xlsx`
- **自动监听**：Excel 文件修改后会自动重新加载并推送到显示端，无需重启服务
- **回退机制**：若 Excel 文件不存在或读取失败，将使用内置的默认数据

> 如需更换数据，直接替换 `data/excel/` 目录下的 Excel 文件即可

## 注意事项

- **首次运行需联网**：项目不包含 `node_modules`，首次启动需联网执行 `npm install` 安装依赖
- 数据存储在服务端内存中，重启服务器后数据将重置
- 如需持久化存储，可扩展使用文件或数据库

## License

MIT
