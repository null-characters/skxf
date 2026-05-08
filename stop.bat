@echo off
chcp 65001 >nul
title 停止 Web 表格服务

echo 正在停止服务...

rem Port (default 3000). Supports: stop.bat 3002  or  set PORT=3002 & stop.bat
set "PORT=%PORT%"
if "%PORT%"=="" set "PORT=%~1"
if "%PORT%"=="" set "PORT=3000"

:: 关闭由 start.bat 打开的控制台窗口（与 start.bat 中 title 一致）
:: /T 会结束该控制台下的子进程（含 node），避免只杀 node 后窗口仍卡在 pause
taskkill /F /FI "WINDOWTITLE eq SKXF-DataBoard" /T >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq Administrator: SKXF-DataBoard" /T >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq 管理员: SKXF-DataBoard" /T >nul 2>&1

:: 若服务由其它方式启动（无上述标题），再按端口清理（HTTP + WebSocket 同端口）
set "FOUND=0"
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%PORT% ^| findstr LISTENING') do (
    set "FOUND=1"
    taskkill /F /PID %%a >nul 2>&1
    echo 已停止占用端口 %PORT% 的进程 (PID: %%a)
)

:: 复查端口是否仍被占用
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%PORT% ^| findstr LISTENING') do (
    echo [warn] 端口 %PORT% 仍被占用 (PID: %%a)，将强制结束...
    taskkill /F /PID %%a >nul 2>&1
)

:: 再次复查（若仍占用，提示用管理员权限运行）
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%PORT% ^| findstr LISTENING') do (
    echo [error] 端口 %PORT% 仍被占用 (PID: %%a)。请以“管理员身份运行” stop.bat 后重试。
    echo         或手动执行: taskkill /F /PID %%a
)

if "%FOUND%"=="0" (
    echo 服务未运行（端口 %PORT% 无 LISTENING）
)

echo 完成
