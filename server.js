const http = require('http');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const xlsx = require('xlsx');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const EXCEL_FILE = path.join(__dirname, '../WeDrive/深圳市比尔达科技有限公司/台账/2026台账/研发项目看板2026.xlsx');
const PUBLIC_ROOT = path.join(__dirname, 'public');
const MAX_WS_PAYLOAD = 256 * 1024;
const EDIT_TOKEN = process.env.DASHBOARD_EDIT_TOKEN || '';
const INSECURE_LAN_EDITS = /^(1|true|yes)$/i.test(process.env.DASHBOARD_ALLOW_INSECURE_LAN_EDITS || '');
const CORS_ORIGIN = process.env.DASHBOARD_CORS_ORIGIN || '';

const MUTATION_TYPES = new Set(['update', 'addRow', 'deleteRow', 'updateTitle']);

const os = require('os');
const dgram = require('dgram');

/** 读取当前非回环 IPv4（DHCP 变更后每次调用都会是最新列表） */
function getLanIPv4Addresses() {
    const list = [];
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                list.push(iface.address);
            }
        }
    }
    return list;
}

const DISCOVERY_PORT_RAW = process.env.DASHBOARD_DISCOVERY_PORT;
const DISCOVERY_PORT =
    DISCOVERY_PORT_RAW === undefined || DISCOVERY_PORT_RAW === ''
        ? 39300
        : Number(DISCOVERY_PORT_RAW);
/** 0、无效端口或过大值关闭 UDP 发现 */
const DISCOVERY_UDP_ENABLED =
    Number.isFinite(DISCOVERY_PORT) && DISCOVERY_PORT > 0 && DISCOVERY_PORT <= 65535;

// 默认表格数据（Excel读取失败时回退）
const defaultTableData = {
    title: '数据看板',
    columns: ['序号', '项目名称', '参数值', '单位', '备注'],
    rows: [
        { id: 1, data: ['1', '生产线速度', '120', '米/分', '正常运行'] },
        { id: 2, data: ['2', '温度', '85', '℃', '稳定'] },
    ]
};

let tableData = { ...defaultTableData };
let nextId = 100;

// Excel 日期序列号转换为日期字符串
function excelDateToString(excelDate) {
    if (typeof excelDate === 'number') {
        const epoch = new Date(1899, 11, 30);
        const date = new Date(epoch.getTime() + excelDate * 24 * 60 * 60 * 1000);
        return date.toLocaleDateString('zh-CN');
    }
    return excelDate || '';
}

// 固定标题
const FIXED_TITLE = 'BILLDA研发项目管理看板';

// 列宽配置（紧凑布局，总宽约70em适配1920x1080大屏）
const COLUMN_WIDTHS = {
    '项目名称': '8em',
    '申请人': '4em',
    '等级': '2.5em',
    '项目组别': '4em',
    '负责人': '4em',
    '项目阶段': '6em',
    '计划开始': '5em',
    '计划完成': '5em',
    '当前状态': '4em',
    '延期天数': '4em',
    '当前项目任务': '7em',
    '执行人': '4em',
    '计划完成时间': '5em',
    '风险状态': '4em',
    '任务延期天数': '4.5em',
};

// 读取 Excel 文件（全景计划表，前15列）
function readExcelFile() {
    try {
        if (!fs.existsSync(EXCEL_FILE)) {
            console.log('Excel 文件不存在，使用默认数据');
            return null;
        }
        
        const workbook = xlsx.readFile(EXCEL_FILE);
        // 读取第三张表"全景计划"
        const sheetName = workbook.SheetNames[2];
        if (sheetName !== '全景计划') {
            console.log(`警告: 第三张表不是"全景计划"，而是"${sheetName}"`);
        }
        const worksheet = workbook.Sheets[sheetName];
        
        // 读取为二维数组，保留空单元格
        const rawData = xlsx.utils.sheet_to_json(worksheet, { header: 1, defval: '' });
        
        if (rawData.length < 2) {
            console.log('Excel 数据行数不足');
            return null;
        }
        
        // 固定标题
        const title = FIXED_TITLE;

        // 第一行作为列头，只取前15列
        const maxCols = 15;
        const headerRow = rawData[0];
        const columns = headerRow.slice(0, maxCols);

        // 计算列宽数组
        const colWidths = columns.map(col => COLUMN_WIDTHS[col] || '8em');

        // "当前状态"列的索引
        const statusColIdx = columns.indexOf('当前状态');

        // 剩余行作为数据
        const rows = [];
        let idCounter = 1;

        for (let i = 1; i < rawData.length; i++) {
            const rawRow = rawData[i].slice(0, maxCols);

            // 跳过"当前状态"为"已完成"的行
            if (statusColIdx >= 0 && String(rawRow[statusColIdx] || '').trim() === '已完成') {
                continue;
            }
            const rowData = rawRow.map((cell, idx) => {
                // 处理日期列（根据列名判断）
                const colName = columns[idx] || '';
                if (colName.includes('开始') || colName.includes('完成') || colName.includes('时间')) {
                    return excelDateToString(cell);
                }
                // 处理数字0显示为空或保留
                if (cell === 0) return '0';
                return cell || '';
            });

            // 只添加非空行（至少有一个非空单元格）
            if (rowData.some(cell => cell !== '' && cell !== '0')) {
                rows.push({ id: idCounter++, data: rowData });
            }
        }
        
        console.log(`Excel 读取成功: ${title}, ${columns.length} 列, ${rows.length} 行`);
        
        return { title, columns, rows, colWidths };
    } catch (error) {
        console.error('读取 Excel 文件失败:', error.message);
        return null;
    }
}

// 广播数据给所有客户端（在 wss 定义后初始化）
let broadcastData = null;

// 初始化数据
function initData() {
    const excelData = readExcelFile();
    if (excelData) {
        tableData = excelData;
        nextId = tableData.rows.length + 1;
    }
}

// 监听 Excel 文件变化
function watchExcelFile() {
    if (!fs.existsSync(EXCEL_FILE)) {
        console.log('Excel 文件不存在，跳过监听');
        return;
    }
    
    let lastModified = fs.statSync(EXCEL_FILE).mtimeMs;
    
    fs.watchFile(EXCEL_FILE, { interval: 1000 }, (curr, prev) => {
        if (curr.mtimeMs !== lastModified) {
            lastModified = curr.mtimeMs;
            console.log('\n检测到 Excel 文件变化，重新读取...');
            
            const newData = readExcelFile();
            if (newData) {
                tableData = newData;
                nextId = tableData.rows.length + 1;
                broadcastData();
            }
        }
    });
    
    console.log('正在监听 Excel 文件变化:', EXCEL_FILE);
}

function normalizeUrlPath(rawUrl) {
    if (!rawUrl) return '/';
    let p = rawUrl.split('?')[0].split('#')[0];
    try {
        p = decodeURIComponent(p);
    } catch (e) { /* ignore */ }
    p = p.replace(/\/+/g, '/');
    if (p.length > 1 && p.endsWith('/')) {
        p = p.slice(0, -1);
    }
    return p || '/';
}

function isLoopbackAddress(addr) {
    if (!addr) return false;
    const a = String(addr);
    return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

/** 防止 /public/../../ 等路径穿越，仅允许落在 public 目录内 */
function resolvePublicFile(relFromPublic) {
    if (relFromPublic == null || relFromPublic === '') return null;
    const rel = path.normalize(relFromPublic);
    if (rel === '.' || rel === '..') return null;
    const candidate = path.resolve(PUBLIC_ROOT, rel);
    const pub = path.resolve(PUBLIC_ROOT);
    if (candidate === pub) return null;
    const relative = path.relative(pub, candidate);
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) return null;
    return candidate;
}

function baseSecurityHeaders() {
    return {
        'X-Content-Type-Options': 'nosniff',
        'Referrer-Policy': 'strict-origin-when-cross-origin',
    };
}

function corsHeadersForRequest(req) {
    if (!CORS_ORIGIN) return {};
    const origin = req.headers.origin;
    if (origin && origin === CORS_ORIGIN) {
        return {
            'Access-Control-Allow-Origin': origin,
            'Vary': 'Origin',
        };
    }
    return {};
}

// HTTP 服务器（WebSocket 复用同一端口，避免局域网设备仅放行 3000 时无法连上 3001）
const server = http.createServer((req, res) => {
    const urlPath = normalizeUrlPath(req.url);
    const method = req.method || 'GET';
    const sec = baseSecurityHeaders();
    const cors = corsHeadersForRequest(req);

    if (urlPath === '/' || urlPath === '/index.html') {
        serveIndexHtml(res, sec);
    } else if (urlPath === '/api/table-state') {
        if (method === 'OPTIONS') {
            const opt = {
                ...sec,
                ...cors,
                'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type',
            };
            if (CORS_ORIGIN) {
                opt['Access-Control-Max-Age'] = '86400';
            }
            res.writeHead(204, opt);
            res.end();
            return;
        }
        if (method !== 'GET' && method !== 'HEAD') {
            res.writeHead(405, { ...sec, 'Content-Type': 'text/plain; charset=UTF-8' });
            res.end('Method Not Allowed');
            return;
        }
        const headers = {
            ...sec,
            ...cors,
            'Content-Type': 'application/json; charset=UTF-8',
            'Cache-Control': 'no-store',
        };
        res.writeHead(200, headers);
        if (method === 'HEAD') {
            res.end();
            return;
        }
        res.end(JSON.stringify(tableData));
    } else if (urlPath === '/manifest.json') {
        serveFile(res, path.join(PUBLIC_ROOT, 'manifest.json'), 'application/json', sec);
    } else if (urlPath.startsWith('/public/')) {
        const rel = urlPath.slice('/public/'.length);
        const safePath = resolvePublicFile(rel);
        if (!safePath) {
            res.writeHead(404, { ...sec, 'Content-Type': 'text/plain; charset=UTF-8' });
            res.end('Not Found');
            return;
        }
        serveFile(res, safePath, getContentType(safePath), sec);
    } else {
        res.writeHead(404, { ...sec, 'Content-Type': 'text/plain; charset=UTF-8' });
        res.end('Not Found');
    }
});

function serveIndexHtml(res, extraHeaders = {}) {
    const fullPath = path.join(PUBLIC_ROOT, 'index.html');
    fs.readFile(fullPath, 'utf8', (err, data) => {
        if (err) {
            res.writeHead(500, { ...extraHeaders });
            res.end('Error loading file');
            return;
        }
        res.writeHead(200, {
            ...extraHeaders,
            'Content-Type': 'text/html; charset=UTF-8',
            'X-Frame-Options': 'SAMEORIGIN',
        });
        res.end(data);
    });
}

function serveFile(res, filePath, contentType, extraHeaders = {}) {
    const fullPath = path.isAbsolute(filePath) ? filePath : path.join(__dirname, filePath);
    fs.readFile(fullPath, (err, data) => {
        if (err) {
            res.writeHead(404, { ...extraHeaders, 'Content-Type': 'text/plain; charset=UTF-8' });
            res.end('Not Found');
            return;
        }
        res.writeHead(200, { ...extraHeaders, 'Content-Type': contentType });
        res.end(data);
    });
}

const wss = new WebSocket.Server({ server, maxPayload: MAX_WS_PAYLOAD });

broadcastData = function() {
    let sent = 0;
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(JSON.stringify({ type: 'update', data: tableData }));
            sent++;
        }
    });
    console.log(`数据已广播: ${sent} 个 WebSocket 客户端在线（共 ${wss.clients.size} 个连接）`);
};

function applyMutation(msg) {
    if (msg.type === 'update') {
        const { rowId, colIndex, value } = msg;
        const row = tableData.rows.find(r => r.id === rowId);
        if (row && typeof colIndex === 'number' && colIndex >= 0 && colIndex < row.data.length) {
            row.data[colIndex] = value == null ? '' : String(value);
        }
    } else if (msg.type === 'addRow') {
        const colCount = Math.max(1, tableData.columns.length);
        const empty = Array(colCount).fill('');
        empty[0] = String(tableData.rows.length + 1);
        const newRow = { id: nextId++, data: empty };
        tableData.rows.push(newRow);
    } else if (msg.type === 'deleteRow') {
        const { rowId } = msg;
        tableData.rows = tableData.rows.filter(r => r.id !== rowId);
        tableData.rows.forEach((row, idx) => {
            if (row.data.length) row.data[0] = String(idx + 1);
        });
    } else if (msg.type === 'updateTitle') {
        tableData.title = msg.title == null ? '' : String(msg.title);
    }
}

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress || '';
    let canMutate = INSECURE_LAN_EDITS;
    if (!canMutate) {
        canMutate = isLoopbackAddress(ip);
    }
    if (INSECURE_LAN_EDITS) {
        console.warn('[WS][安全] DASHBOARD_ALLOW_INSECURE_LAN_EDITS 已开启：任意客户端可通过 WebSocket 修改内存中的表格数据');
    }

    ws._canMutate = canMutate;
    console.log(`[WS] 新连接 from ${ip}（可写: ${ws._canMutate}，当前连接数 ${wss.clients.size}）`);

    ws.send(JSON.stringify({ type: 'init', data: tableData }));

    ws.on('error', (err) => {
        console.error('[WS] 连接错误:', err.message);
    });

    ws.on('close', (code, reason) => {
        const why = reason && reason.length ? reason.toString() : '';
        console.log(`[WS] 断开 code=${code} ${why}（剩余 ${wss.clients.size}）`);
    });

    ws.on('message', (message) => {
        try {
            const msg = JSON.parse(message.toString());

            if (msg.type === 'auth') {
                if (INSECURE_LAN_EDITS) {
                    ws.send(JSON.stringify({ type: 'authOk', note: 'insecure_lan' }));
                    return;
                }
                if (!EDIT_TOKEN) {
                    ws.send(JSON.stringify({
                        type: 'authFail',
                        code: 'NO_SERVER_TOKEN',
                        message: '服务端未设置 DASHBOARD_EDIT_TOKEN，仅本机回环地址可编辑',
                    }));
                    return;
                }
                if (msg.token === EDIT_TOKEN) {
                    ws._canMutate = true;
                    ws.send(JSON.stringify({ type: 'authOk' }));
                    console.log(`[WS] 远端已认证编辑权限 from ${ip}`);
                } else {
                    ws.send(JSON.stringify({ type: 'authFail', code: 'BAD_TOKEN', message: '令牌错误' }));
                    console.warn(`[WS] 拒绝错误编辑令牌 from ${ip}`);
                }
                return;
            }

            if (MUTATION_TYPES.has(msg.type) && !ws._canMutate) {
                ws.send(JSON.stringify({
                    type: 'error',
                    code: 'FORBIDDEN',
                    message: '无写权限：请在本机编辑，或设置 DASHBOARD_EDIT_TOKEN 并通过 ?mode=edit&token= 连接后先发 auth 消息',
                }));
                console.warn(`[WS] 拒绝未授权写操作 (${msg.type}) from ${ip}`);
                return;
            }

            if (MUTATION_TYPES.has(msg.type)) {
                applyMutation(msg);
                wss.clients.forEach(client => {
                    if (client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({ type: 'update', data: tableData }));
                    }
                });
            }
        } catch (e) {
            console.error('消息处理错误:', e.message);
        }
    });
});

initData();
watchExcelFile();

function getContentType(filePath) {
    const ext = path.extname(filePath);
    const types = {
        '.html': 'text/html',
        '.js': 'application/javascript',
        '.css': 'text/css',
        '.json': 'application/json',
        '.png': 'image/png',
        '.jpg': 'image/jpeg',
        '.svg': 'image/svg+xml',
    };
    return types[ext] || 'text/plain';
}

/** 多网卡时优先选与客户端同一 C 段（/24）的地址，便于 DHCP 变更后 suggestUrl 仍可用 */
function pickLanIpForClient(clientIp, ips) {
    if (!ips.length) return null;
    if (!clientIp) return ips[0];
    const m = /^(\d+)\.(\d+)\.(\d+)\./.exec(clientIp);
    if (!m) return ips[0];
    const prefix = `${m[1]}.${m[2]}.${m[3]}.`;
    const hit = ips.find((ip) => ip.startsWith(prefix));
    return hit || ips[0];
}

function startDiscoveryUdp() {
    if (!DISCOVERY_UDP_ENABLED) {
        const hint =
            DISCOVERY_PORT_RAW === undefined || DISCOVERY_PORT_RAW === ''
                ? ''
                : `（当前 DASHBOARD_DISCOVERY_PORT=${DISCOVERY_PORT_RAW}）`;
        console.log(`  局域网发现: 已关闭${hint}`);
        return;
    }
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    sock.on('error', (err) => {
        console.error('[发现] UDP 错误:', err.message);
    });
    sock.on('message', (msg, rinfo) => {
        if (msg.toString('utf8').trim() !== 'SKXF_DISCOVER') return;
        const ips = getLanIPv4Addresses();
        const preferred = pickLanIpForClient(rinfo.address, ips);
        const payload = JSON.stringify({
            schema: 1,
            httpPort: PORT,
            ips,
            suggestUrl: preferred ? `http://${preferred}:${PORT}/?mode=display` : null,
            displayPath: '/?mode=display',
        });
        sock.send(Buffer.from(payload, 'utf8'), rinfo.port, rinfo.address, (err) => {
            if (err) console.warn('[发现] 回复失败:', err.message);
        });
        console.log(`[发现] → ${rinfo.address}:${rinfo.port} 已回复 (${ips.length} 个 IPv4)`);
    });
    sock.bind(DISCOVERY_PORT, '0.0.0.0', () => {
        console.log(
            `  局域网发现: UDP ${DISCOVERY_PORT}，广播或单播发送 UTF-8 载荷 SKXF_DISCOVER，收到 JSON（httpPort、ips、suggestUrl）`,
        );
    });
}

server.listen(PORT, HOST, () => {
    const lanIPv4List = getLanIPv4Addresses();
    console.log(`\n========================================`);
    console.log(`  Web 表格服务已启动`);
    console.log(`========================================`);
    console.log(`  编辑页面（本地）: http://localhost:${PORT}?mode=edit`);
    console.log(`  表格 JSON（大屏拉数）: http://localhost:${PORT}/api/table-state`);
    if (lanIPv4List.length === 0) {
        console.log(`  显示页面（设备）: http://<本机局域网IPv4>:${PORT}/?mode=display  （本机 ipconfig 查看）`);
    } else {
        console.log(`  显示页面（设备，在电视浏览器中任选可达的本机地址）:`);
        lanIPv4List.forEach((ip) => {
            console.log(`    http://${ip}:${PORT}/?mode=display`);
        });
    }
    if (INSECURE_LAN_EDITS) {
        console.log(`  安全: 已开启 DASHBOARD_ALLOW_INSECURE_LAN_EDITS（任意局域网客户端可改表，不推荐生产）`);
    } else if (EDIT_TOKEN) {
        console.log(`  安全: 已设置 DASHBOARD_EDIT_TOKEN — 非本机编辑请使用 ?mode=edit&token=<令牌>`);
    } else {
        console.log(`  安全: 未设置 DASHBOARD_EDIT_TOKEN — 仅本机回环 WebSocket 可写；电视/大屏为只读`);
    }
    if (CORS_ORIGIN) {
        console.log(`  安全: 已启用 CORS，仅允许 Origin: ${CORS_ORIGIN}`);
    }
    console.log(`========================================\n`);
    startDiscoveryUdp();
});
