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

# 获取本机 IP
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "localhost")

echo "[信息] 本机 IP: $LOCAL_IP"
echo ""

# 检查 ADB 是否可用
if command -v adb &> /dev/null; then
    # 检查是否有连接的 Android 设备
    ADB_DEVICES=$(adb devices | grep -v "List of devices" | grep "device" | wc -l)
    if [ "$ADB_DEVICES" -gt 0 ]; then
        echo "[信息] 检测到 Android 设备，正在打开网页..."
        
        # 在 Android 设备上打开 Chrome 浏览器访问网页
        adb shell am start -a android.intent.action.VIEW \
            -d "http://${LOCAL_IP}:3000/?mode=display" \
            com.android.chrome/com.google.android.apps.chrome.Main
        
        echo "[完成] 已在 Android 设备上打开网页"
        echo ""
        
        # 可选：设置沉浸式全屏模式（隐藏系统状态栏）
        echo "[提示] 如需隐藏系统状态栏，可运行："
        echo "       adb shell settings put global policy_control immersive.full=*"
        echo ""
    else
        echo "[提示] 未检测到 Android 设备"
        echo "       请确保设备已通过 USB 连接并开启 USB 调试"
        echo ""
    fi
else
    echo "[提示] ADB 未安装，跳过设备打开步骤"
    echo "       如需自动打开 Android 设备网页，请安装 Android Platform Tools"
    echo ""
fi

echo "[信息] 正在启动服务器..."
echo ""
node server.js