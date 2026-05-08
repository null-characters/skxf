#!/bin/bash

# 停止 Web 表格服务

echo "正在停止服务..."

# 端口（默认 3000）。支持：./stop.sh 3002 或 PORT=3002 ./stop.sh
PORT="${PORT:-${1:-3000}}"

# 查找并杀死监听端口的进程（HTTP + WebSocket 同端口）
PIDS=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')

if [ -n "$PIDS" ]; then
    echo "将停止占用端口 $PORT 的进程: $PIDS"
    for PID in $PIDS; do
        kill "$PID" 2>/dev/null || true
    done
    # 等待端口释放（最多 2 秒），不行则强杀
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.2
        STILL=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')
        [ -z "$STILL" ] && break
    done
    STILL=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')
    if [ -n "$STILL" ]; then
        echo "[警告] 端口 $PORT 仍被占用，将强制结束: $STILL"
        for PID in $STILL; do
            kill -9 "$PID" 2>/dev/null || true
        done
    fi
    if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "[警告] 端口 $PORT 仍处于监听状态，请手动检查："
        lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
        echo "[提示] 如需强制结束，可执行：kill -9 \$(lsof -tiTCP:$PORT -sTCP:LISTEN)"
        echo "[提示] 若提示无权限，请加 sudo。"
        exit 1
    else
        echo "已停止端口 $PORT 上的服务"
    fi
else
    echo "服务未运行（端口 $PORT 无监听进程）"
fi

echo "完成"
