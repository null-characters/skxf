@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem 加 -ResetChrome 可清除电视端 Chrome 全部数据后只打开一页（解决多标签/错误地址卡住）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0configure-android-display.ps1" %*
