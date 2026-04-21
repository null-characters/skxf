@echo off
chcp 65001 >nul
title 停止 Web 表格服务

echo 正在停止服务...

:: 关闭由 start.bat 打开的控制台窗口（与 start.bat 中 title 一致）
:: /T 会结束该控制台下的子进程（含 node），避免只杀 node 后窗口仍卡在 pause
taskkill /F /FI "WINDOWTITLE eq SKXF-DataBoard" /T >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq Administrator: SKXF-DataBoard" /T >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq 管理员: SKXF-DataBoard" /T >nul 2>&1

:: 若服务由其它方式启动（无上述标题），再按端口清理
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3000 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo 已停止占用端口 3000 的进程 (PID: %%a)
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3001 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo 已停止占用端口 3001 的进程 (PID: %%a)
)

echo 完成
