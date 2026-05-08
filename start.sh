#!/bin/bash
# 数据看板 - macOS 启动脚本

echo "========================================"
echo "  数据看板 - 启动程序"
echo "========================================"
echo ""

# 检查 Node.js 是否已安装
if ! command -v node &> /dev/null; then
    echo "[错误] 未检测到 Node.js，请先安装！"
    echo ""
    echo "下载地址: https://nodejs.org/"
    echo "选择 LTS 版本下载安装"
    echo ""
    exit 1
fi

echo "[信息] Node.js 版本:"
node --version
echo ""

# 端口（默认 3000）。支持：PORT=3002 ./start.sh 或 ./start.sh 3002
PORT="${PORT:-${1:-3000}}"

# 检查是否需要安装依赖
if [ ! -d "node_modules/ws" ]; then
    echo "[信息] 首次运行，正在安装依赖..."
    npm install
    if [ $? -ne 0 ]; then
        echo "[错误] 依赖安装失败！"
        exit 1
    fi
    echo ""
fi

# 启动前检查端口占用，避免 EADDRINUSE
LISTEN_PID=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -n 1)
if [ -n "$LISTEN_PID" ]; then
    echo "[错误] 端口 $PORT 已被占用（PID: $LISTEN_PID）。"
    echo "[提示] 你可以先执行: ./stop.sh $PORT"
    echo "[提示] 或改用其它端口启动: PORT=3002 ./start.sh"
    echo ""
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
    exit 1
fi

# 获取本机 IP
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "localhost")

echo "[信息] 本机 IP: $LOCAL_IP"
echo "[提示] 信息发布屏请在浏览器手动打开: http://${LOCAL_IP}:${PORT}/?mode=display"
echo ""

echo "[信息] 正在启动服务器..."
echo ""
# Optional: export DASHBOARD_EDIT_TOKEN=change-me
# Optional: export DASHBOARD_CORS_ORIGIN=http://192.168.1.100:3000
# See docs/SECURITY_AUDIT.md
PORT="$PORT" node server.js
