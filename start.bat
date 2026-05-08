@echo off
chcp 65001 >nul
title SKXF-DataBoard

echo ========================================
echo   Data Board - Start
echo ========================================
echo.

where node >nul 2>nul
if errorlevel 1 goto no_node

echo [info] Node.js version:
node --version
echo.

rem Port (default 3000). Supports: set PORT=3002 & start.bat  or  start.bat 3002
set "PORT=%PORT%"
if "%PORT%"=="" set "PORT=%~1"
if "%PORT%"=="" set "PORT=3000"

if not exist "node_modules\" goto install_deps
if not exist "node_modules\ws\package.json" goto install_deps
if not exist "node_modules\xlsx\package.json" goto install_deps
goto run_server

:install_deps
echo [info] Installing dependencies (first run or incomplete)...
call npm install
if errorlevel 1 (
    echo [error] npm install failed.
    pause
    exit /b 1
)
echo.

:run_server
echo [info] Starting server...
echo.
rem Optional security (see docs\SECURITY_AUDIT.md):
rem set DASHBOARD_EDIT_TOKEN=change-me
rem set DASHBOARD_CORS_ORIGIN=http://192.168.1.100:3000
rem Preflight: check port to avoid EADDRINUSE
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%PORT% ^| findstr LISTENING') do (
    echo [error] Port %PORT% is already in use (PID: %%a).
    echo [hint] Run: stop.bat %PORT%
    echo [hint] Or start on another port: set PORT=3002 ^& start.bat
    echo.
    netstat -ano | findstr :%PORT% | findstr LISTENING
    pause
    exit /b 1
)

set "PORT=%PORT%"
node server.js
pause
exit /b 0

:no_node
echo [error] Node.js not found. Install LTS from https://nodejs.org/
echo         Enable "Add to PATH" during setup.
echo.
pause
exit /b 1
