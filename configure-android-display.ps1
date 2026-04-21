# Requires: adb in PATH (Android Platform-Tools)
# displayHost = 运行 node 的电脑 IP（浏览器打开的地址）
# adbDevice   = 显示端 Android 的 adb 地址，默认 192.168.0.158:5555

param(
    [string] $Device = "",
    [int] $Port = 3000,
    [switch] $ResetChrome
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Read-ProjectConfig {
    $cfg = Join-Path $root "config.json"
    $ex = Join-Path $root "config.example.json"
    foreach ($p in @($cfg, $ex)) {
        if (Test-Path $p) {
            try {
                return Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json
            } catch { }
        }
    }
    return $null
}

function Get-DisplayHost {
    $j = Read-ProjectConfig
    if ($j) {
        $h = $j.displayHost
        if ($h -and ($h -is [string]) -and $h.Trim()) {
            return $h.Trim()
        }
    }
    return "192.168.0.188"
}

function Get-AdbDevice {
    $j = Read-ProjectConfig
    if ($j) {
        $d = $j.adbDevice
        if ($d -and ($d -is [string]) -and $d.Trim()) {
            return $d.Trim()
        }
    }
    return "192.168.0.158:5555"
}

$displayHost = Get-DisplayHost
$adbDevice = if ($Device -and $Device.Trim()) { $Device.Trim() } else { Get-AdbDevice }
$url = "http://${displayHost}:${Port}/?mode=display"

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Error "adb not found. Install Platform-Tools and add to PATH, or open a new terminal after winget install."
}

Write-Host "ADB device (显示端): $adbDevice"
Write-Host "Open in Chrome: $url  (displayHost = 服务器电脑)"
Write-Host ""

& adb connect $adbDevice
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 必须让 adb 把参数直接交给设备上的 am，不要包在一层 "shell \"...\"" 里，
# 否则 -n 可能被拼进 -d 的 URL（浏览器里会一直加载错误地址）。
if ($ResetChrome) {
    Write-Host "ResetChrome: 清除 Chrome 全部数据（关掉所有标签、错误 URL、登录状态）..."
    & adb -s $adbDevice shell pm clear com.android.chrome
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Start-Sleep -Seconds 2
} else {
    & adb -s $adbDevice shell am force-stop com.android.chrome
    Start-Sleep -Milliseconds 600
}

& adb -s $adbDevice shell am start -a android.intent.action.VIEW -d $url -n com.android.chrome/com.google.android.apps.chrome.Main --activity-clear-top
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Done. Ensure this PC uses static IP matching displayHost ($displayHost) and node server is listening on port $Port."
