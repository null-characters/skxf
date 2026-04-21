#!/bin/bash

# 停止 Web 表格服务

echo "正在停止服务..."

# 查找并杀死监听 3000 端口的进程
PID_3000=$(lsof -ti:3000 2>/dev/null)
PID_3001=$(lsof -ti:3001 2>/dev/null)

if [ -n "$PID_3000" ]; then
    kill $PID_3000 2>/dev/null
    echo "已停止 HTTP 服务 (端口 3000, PID: $PID_3000)"
fi

if [ -n "$PID_3001" ]; then
    kill $PID_3001 2>/dev/null
    echo "已停止 WebSocket 服务 (端口 3001, PID: $PID_3001)"
fi

if [ -z "$PID_3000" ] && [ -z "$PID_3001" ]; then
    echo "服务未运行"
fi

echo "完成"
