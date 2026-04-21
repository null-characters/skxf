# 数据看板服务 — 安全与边界审查留痕

**审查对象**：`server.js`、`public/index.html` 及运行方式（本机 `0.0.0.0:3000`，局域网电视浏览器访问）。  
**审查日期**：2026-04-21  
**结论摘要**：默认部署下，服务对局域网**可读**表格与 WebSocket 推送；**写操作**在加固后默认仅本机回环或持有令牌者可执行；静态文件路径已防穿越；API CORS 默认不再对任意源开放。

---

## 1. 威胁模型与边界

| 角色 | 典型访问方式 | 预期能力 |
|------|----------------|----------|
| 本机编辑人员 | `http://localhost:PORT/?mode=edit` | 读写内存中的表格状态（不写回 Excel 文件） |
| 大屏 / 电视 | `http://<PC 局域网 IP>:PORT/?mode=display` | 仅展示与接收广播，**不应**具备改表能力 |
| 同网段其他主机 | 任意 HTTP/WS 客户端 | 默认可拉取 `/api/table-state`、可连 WebSocket 收推送；**不可**在未授权时改表 |

**边界假设**：局域网并非可信环境；端口 3000 对网段广播后，任意设备均可尝试连接。因此「大屏只读、编辑可验」是合理默认。

---

## 2. 发现的问题（审查时状态）

### 2.1 WebSocket 任意写（高危）

- **描述**：任意连上 WebSocket 的客户端可发送 `update` / `addRow` / `deleteRow` / `updateTitle`，服务端无鉴别即修改内存表并广播。
- **影响**：同网段设备可篡改大屏内容、干扰发布；与业务「仅本机编辑」边界不符。
- **处置**：见 §3.1 — 默认仅回环可写；可选 `DASHBOARD_EDIT_TOKEN` 供非本机编辑；兼容旧行为可用 `DASHBOARD_ALLOW_INSECURE_LAN_EDITS`（不推荐）。

### 2.2 静态路径 `/public/` 可能路径穿越（中危）

- **描述**：`urlPath` 经解码后拼入 `path.join(__dirname, urlPath)`，未校验最终路径是否落在 `public` 目录内。
- **影响**：理论上可能读取项目内其它文件（视 OS 与编码而定）。
- **处置**：见 §3.2 — `resolvePublicFile` + `path.resolve` / `path.relative` 约束。

### 2.3 API CORS `*`（低～中危）

- **描述**：`/api/table-state` 曾使用 `Access-Control-Allow-Origin: *`。
- **影响**：任意网站可通过浏览器用户代理跨域读取 JSON（若用户访问恶意页且浏览器策略允许）；表格数据若为内部信息则扩大暴露面。
- **处置**：见 §3.3 — 默认不附加宽松 CORS；需跨域时通过 `DASHBOARD_CORS_ORIGIN` 显式允许单一源。

### 2.4 无 WebSocket 消息大小上限（低危）

- **描述**：超大消息可能导致内存与事件循环压力。
- **处置**：`maxPayload` 限制（如 256KB）。

### 2.5 缺少通用 HTTP 安全头（低危）

- **描述**：未设置 `X-Content-Type-Options` 等，对纯内网场景影响有限，但利于减少 MIME 嗅探等边缘风险。
- **处置**：对主要响应附加基础安全头；HTML 使用 `X-Frame-Options: SAMEORIGIN`（同源嵌入大屏场景可接受）。

### 2.6 持久化与 Excel

- **描述**：编辑仅影响**进程内** `tableData`；Excel 由文件变更触发**重读覆盖**内存。不存在「通过接口写 Excel」的暴露，但运维需知：磁盘上改表会覆盖内存编辑结果。
- **建议**：编辑场景明确是否需「导出/写回 Excel」；若需要应单独设计鉴权与文件锁。

### 2.7 前端 XSS 面

- **描述**：表头在编辑模式下曾直接拼入 HTML（列名来自 Excel）。若表头含恶意字符串，存在 XSS 风险。
- **处置**：编辑模式对列名使用 `escapeHtml` 与数据单元格一致（见代码变更）。

---

## 3. 已实施的缓解措施

### 3.1 WebSocket 写权限

- 本机回环地址（`127.0.0.1`、`::1`、`::ffff:127.0.0.1`）连接：**允许**写。
- 非回环：**默认拒绝**写；若设置环境变量 `DASHBOARD_EDIT_TOKEN`，客户端可在连接后发送 `{ "type": "auth", "token": "<令牌>" }`，或使用页面 `?mode=edit&token=<令牌>` 由前端自动发送认证。
- `DASHBOARD_ALLOW_INSECURE_LAN_EDITS=1`：恢复「任意客户端可写」（**仅用于兼容/调试**）。

### 3.2 静态文件

- `/public/*` 映射到 `public` 目录内解析后的真实路径；解析结果必须位于 `public` 根下，否则 404。

### 3.3 CORS

- 未设置 `DASHBOARD_CORS_ORIGIN` 时：不对 API 响应添加 `Access-Control-Allow-Origin`（同源访问不受影响）。
- 设置时：仅当请求 `Origin` 与该值一致时回显，用于受控的跨域拉数。

### 3.4 其它

- `PORT` / `HOST` 可通过环境变量覆盖（便于反向代理或仅监听本机）。
- HTTP 基础安全头；WebSocket `maxPayload`。
- `applyMutation` 对 `colIndex`、`title`、`value` 做类型与边界约束，减少异常输入导致的状态损坏。

---

## 4. 配置速查

| 变量 | 含义 |
|------|------|
| `PORT` | HTTP/WS 端口，默认 `3000` |
| `HOST` | 监听地址，默认 `0.0.0.0` |
| `DASHBOARD_EDIT_TOKEN` | 非本机编辑时 WebSocket 写权限令牌 |
| `DASHBOARD_ALLOW_INSECURE_LAN_EDITS` | 设为 `1`/`true` 则任意客户端可写（不推荐） |
| `DASHBOARD_CORS_ORIGIN` | 允许跨域访问 API 的单一 Origin（完整字符串） |

---

## 5. 残留风险与运维建议

1. **无 TLS**：局域网 HTTP/WS 明文传输，中间人可读改内容；高敏感场景建议前置 HTTPS/WSS 反向代理或限定 VLAN。
2. **无账号体系**：令牌为共享密钥，泄漏即等同编辑权限；应定期更换并限制知悉范围。
3. **防火墙**：若仅需大屏访问，可在操作系统防火墙将 3000 限制为电视 IP 或指定网段。
4. **依赖漏洞**：`ws`、`xlsx` 等需随项目定期 `npm audit` / 升级。
5. **日志**：服务端会对拒绝的写操作打印警告，便于留痕排查。

---

## 6. 变更对照（便于复核）

| 文件 | 变更要点 |
|------|-----------|
| `server.js` | 写权限模型、路径安全、CORS 可配、安全头、`maxPayload`、变异逻辑校验、`PORT`/`HOST` 环境变量 |
| `public/index.html` | 令牌认证与错误提示、非本机编辑提示、列名转义、WS 消息 try/catch |
| `docs/SECURITY_AUDIT.md` | 本文档 |
| `start.bat` / `start.sh` | 注释示例：如何设置环境变量（可选） |

---

*本文件为审查与整改留痕；后续架构或行为变更时请同步更新本节。*
