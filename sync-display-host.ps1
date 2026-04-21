# 将本机局域网 IPv4 写入 config.json 的 displayHost（保留已有 adbDevice）
# 不运行本脚本也可正常使用：直接 start.bat，电视手动输入 ipconfig 里看到的地址即可。

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$cfgPath = Join-Path $root "config.json"
$exPath = Join-Path $root "config.example.json"

function Get-LanIPv4 {
    $rows = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {
        $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and
        ($_.PrefixOrigin -eq 'Dhcp' -or $_.PrefixOrigin -eq 'Manual' -or $_.PrefixOrigin -eq 'RouterAdvertisement')
    }
    $prefer = $rows | Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-?Fi|无线|Ethernet|以太网' }
    $pick = ($prefer | Select-Object -First 1)
    if (-not $pick) { $pick = $rows | Select-Object -First 1 }
    if ($pick) { return $pick.IPAddress.Trim() }
    return $null
}

$ip = Get-LanIPv4
if (-not $ip) {
    Write-Error "未找到可用的局域网 IPv4，请检查网卡后重试。"
}

$obj = [ordered]@{ displayHost = $ip; adbDevice = "192.168.0.158:5555" }
if (Test-Path $cfgPath) {
    try {
        $old = Get-Content $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($old.adbDevice -and $old.adbDevice.Trim()) {
            $obj.adbDevice = $old.adbDevice.Trim()
        }
    } catch { }
} elseif (Test-Path $exPath) {
    try {
        $ex = Get-Content $exPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($ex.adbDevice -and $ex.adbDevice.Trim()) {
            $obj.adbDevice = $ex.adbDevice.Trim()
        }
    } catch { }
}

$json = ($obj | ConvertTo-Json -Compress)
[System.IO.File]::WriteAllText($cfgPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "已写入 $cfgPath"
Write-Host "  displayHost = $ip"
Write-Host "  adbDevice   = $($obj.adbDevice)"
Write-Host ""
Write-Host "电视可打开: http://${ip}:3000/?mode=display"
