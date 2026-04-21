@echo off
chcp 65001 >nul
title 停止 Web 表格服务

echo 正在停止服务...

:: 查找并杀死监听 3000 端口的进程
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3000 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo 已停止 HTTP 服务 (端口 3000, PID: %%a)
)

:: 查找并杀死监听 3001 端口的进程
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3001 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo 已停止 WebSocket 服务 (端口 3001, PID: %%a)
)

echo 完成
pause
