@echo off
chcp 65001 >nul
title Data Board

echo ========================================
echo   Data Board - Start
echo ========================================
echo.

where node >nul 2>nul
if errorlevel 1 goto no_node

echo [info] Node.js version:
node --version
echo.

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
node server.js
pause
exit /b 0

:no_node
echo [error] Node.js not found. Install LTS from https://nodejs.org/
echo         Enable "Add to PATH" during setup.
echo.
pause
exit /b 1
