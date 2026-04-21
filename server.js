const http = require('http');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const xlsx = require('xlsx');

const PORT = 3000;
const HOST = '0.0.0.0';
const EXCEL_FILE = path.join(__dirname, 'data/excel/研发项目看板2026.xlsx');

// 本机所有局域网 IPv4（启动时打印，供电视浏览器填写；项目内无 IP 配置文件）
const os = require('os');
const interfaces = os.networkInterfaces();
const lanIPv4List = [];
for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
            lanIPv4List.push(iface.address);
        }
    }
}
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

// 读取 Excel 文件
function readExcelFile() {
    try {
        if (!fs.existsSync(EXCEL_FILE)) {
            console.log('Excel 文件不存在，使用默认数据');
            return null;
        }
        
        const workbook = xlsx.readFile(EXCEL_FILE);
        const sheetName = workbook.SheetNames[0];
        const worksheet = workbook.Sheets[sheetName];
        
        // 读取为二维数组，保留空单元格
        const rawData = xlsx.utils.sheet_to_json(worksheet, { header: 1, defval: '' });
        
        if (rawData.length < 2) {
            console.log('Excel 数据行数不足');
            return null;
        }
        
        // 第一行作为标题
        const title = rawData[0][0] || '数据看板';

        // 第二行作为列头，跳过空列（合并单元格产生的空列）
        const headerRow = rawData[1];
        const skipIndexes = [];
        headerRow.forEach((col, idx) => {
            if (col === '' && idx > 0) skipIndexes.push(idx);
        });
        const columns = headerRow.filter((col, idx) => !skipIndexes.includes(idx));

        // 剩余行作为数据，同样跳过空列
        const rows = [];
        let idCounter = 1;

        for (let i = 2; i < rawData.length; i++) {
            const rawRow = rawData[i].filter((cell, idx) => !skipIndexes.includes(idx));
            const rowData = rawRow.map((cell, idx) => {
                // 处理日期列（根据列名判断）
                const colName = columns[idx] || '';
                if (colName.includes('时间') || colName.includes('日期')) {
                    return excelDateToString(cell);
                }
                // 处理数字0显示为空或保留
                if (cell === 0) return '0';
                return cell || '';
            });

            // 只添加非空行
            if (rowData.some(cell => cell !== '' && cell !== '0')) {
                rows.push({ id: idCounter++, data: rowData });
            }
        }
        
        console.log(`Excel 读取成功: ${title}, ${columns.length} 列, ${rows.length} 行`);
        
        return { title, columns, rows };
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

// HTTP 服务器（WebSocket 复用同一端口，避免局域网设备仅放行 3000 时无法连上 3001）
const server = http.createServer((req, res) => {
    const urlPath = normalizeUrlPath(req.url);
    const method = req.method || 'GET';

    if (urlPath === '/' || urlPath === '/index.html') {
        serveIndexHtml(res);
    } else if (urlPath === '/api/table-state') {
        if (method === 'OPTIONS') {
            res.writeHead(204, {
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type',
            });
            res.end();
            return;
        }
        if (method !== 'GET' && method !== 'HEAD') {
            res.writeHead(405, { 'Content-Type': 'text/plain; charset=UTF-8' });
            res.end('Method Not Allowed');
            return;
        }
        const headers = {
            'Content-Type': 'application/json; charset=UTF-8',
            'Cache-Control': 'no-store',
            'Access-Control-Allow-Origin': '*',
        };
        res.writeHead(200, headers);
        if (method === 'HEAD') {
            res.end();
            return;
        }
        res.end(JSON.stringify(tableData));
    } else if (urlPath === '/manifest.json') {
        serveFile(res, 'public/manifest.json', 'application/json');
    } else if (urlPath.startsWith('/public/')) {
        const filePath = path.join(__dirname, urlPath);
        serveFile(res, filePath, getContentType(filePath));
    } else {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=UTF-8' });
        res.end('Not Found');
    }
});

function serveIndexHtml(res) {
    const fullPath = path.join(__dirname, 'public/index.html');
    fs.readFile(fullPath, 'utf8', (err, data) => {
        if (err) {
            res.writeHead(500);
            res.end('Error loading file');
            return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=UTF-8' });
        res.end(data);
    });
}

function serveFile(res, filePath, contentType) {
    const fullPath = path.isAbsolute(filePath) ? filePath : path.join(__dirname, filePath);
    fs.readFile(fullPath, (err, data) => {
        if (err) {
            res.writeHead(500);
            res.end('Error loading file');
            return;
        }
        res.writeHead(200, { 'Content-Type': contentType });
        res.end(data);
    });
}

const wss = new WebSocket.Server({ server });

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

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress || '';
    console.log(`[WS] 新连接 from ${ip}（当前连接数 ${wss.clients.size}）`);

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
            const msg = JSON.parse(message);

            if (msg.type === 'update') {
                const { rowId, colIndex, value } = msg;
                const row = tableData.rows.find(r => r.id === rowId);
                if (row) {
                    row.data[colIndex] = value;
                }
            } else if (msg.type === 'addRow') {
                const newRow = {
                    id: nextId++,
                    data: [String(tableData.rows.length + 1), '', '', '', '']
                };
                tableData.rows.push(newRow);
            } else if (msg.type === 'deleteRow') {
                const { rowId } = msg;
                tableData.rows = tableData.rows.filter(r => r.id !== rowId);
                tableData.rows.forEach((row, idx) => {
                    row.data[0] = String(idx + 1);
                });
            } else if (msg.type === 'updateTitle') {
                tableData.title = msg.title;
            }

            wss.clients.forEach(client => {
                if (client.readyState === WebSocket.OPEN) {
                    client.send(JSON.stringify({ type: 'update', data: tableData }));
                }
            });
        } catch (e) {
            console.error('消息处理错误:', e);
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

server.listen(PORT, HOST, () => {
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
    console.log(`========================================\n`);
});
