# SKXF 大屏启动器（Android）

← 返回项目主说明：[**`README.md`**](../README.md)（含 **[文档索引](../README.md#文档索引)**）

轻量壳：沉浸式全屏 WebView、`http` 明文、**局域网 UDP 发现**。协议与部署说明与 **[`plan/android.md`](../plan/android.md)**、**[`plan/architecture.md`](../plan/architecture.md)** 一致。

**发现流程**：App 向 **`255.255.255.255:39300`** 发送 **`SKXF_DISCOVER`**（UTF-8）→ 仓库根目录 **[`server.js`](../server.js)** 在 **`DASHBOARD_DISCOVERY_PORT`**（默认 **39300**）上监听并向来源 **单播 JSON** → App 解析 **`suggestUrl`**（或 **`ips`** + **`httpPort`** + **`displayPath`**）。发现端口与载荷须与 **[`DashboardDiscovery.kt`](app/src/main/java/com/skxf/display/DashboardDiscovery.kt)**、服务端 **[`docs/SECURITY_AUDIT.md`](../docs/SECURITY_AUDIT.md)**（环境变量 **`DASHBOARD_DISCOVERY_PORT`**）对齐。

---

## 相关链接（本文档内）

| 主题 | 章节 |
|------|------|
| JDK / SDK / 本机路径 | [环境要求](#环境要求) |
| Android Studio 菜单打包 | [如何用 Android Studio 构建](#如何用-android-studio-构建) |
| 终端 `assembleDebug` | [命令行构建 APK（Debug）](#命令行构建-apkdebug) |
| `adb install` / 启动 Activity | [使用 ADB 安装与启动](#使用-adb-安装与启动) |
| 与后端 JSON 字段 | [与后端对齐](#与后端对齐) |

---

## 环境要求

| 项 | 说明 |
|---|------|
| **JDK** | **17**（与 [`app/build.gradle.kts`](app/build.gradle.kts) 中 `jvmTarget` / `JavaVersion.VERSION_17` 一致） |
| **Android SDK** | 通过 **Android Studio** 安装；**`compileSdk` / `targetSdk` = 34**，**`minSdk` = 24**（见 [`app/build.gradle.kts`](app/build.gradle.kts)） |
| **`local.properties`** | 首次用 Android Studio 打开本 **`android/`** 目录并 **Sync** 后，IDE 会生成该文件，写入本机 **`sdk.dir=...`**（已在 [`.gitignore`](.gitignore) 中忽略，勿提交） |
| **Gradle（Wrapper 分发版）** | [`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties) 指定 **`gradle-8.4-bin`**；首次同步会从网络拉取到本机 Gradle 用户目录（`~/.gradle/wrapper/dists/`） |

### 常见路径（macOS）

以下路径供排查「命令行找不到工具」时对照；请以你本机为准。

| 工具 / 变量 | 典型位置 |
|-------------|----------|
| **Android SDK** | `~/Library/Android/sdk`；环境变量可设 **`export ANDROID_HOME="$HOME/Library/Android/sdk"`** |
| **`adb`** | **`$ANDROID_HOME/platform-tools/adb`**（未加入 PATH 时需写全路径） |
| **Gradle 8.4 可执行文件**（Studio 同步后） | `~/.gradle/wrapper/dists/gradle-8.4-bin/<随机目录名>/gradle-8.4/bin/gradle`；`<随机目录名>` 因缓存而异，可用终端展开通配符定位 |

Windows 上 SDK 常见为 **`%LOCALAPPDATA%\Android\Sdk`**，`adb` 在 **`platform-tools\adb.exe`**。

## 如何用 Android Studio 构建

1. 安装 **Android Studio**（含 Android SDK）。
2. 菜单 **Open**，选择仓库里的 **`android`** 目录。
3. 首次同步 Gradle 后：**Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 将生成的 **`app/build/outputs/apk/debug/app-debug.apk`** 拷到大屏设备安装（或数据线 / ADB）。

> 本仓库 **未提交** `gradlew` / `gradle-wrapper.jar` 时，命令行需依赖本机已下载的 Gradle 分发包（见上表），或先在本目录执行一次 **`gradle wrapper`**（本机需已安装 Gradle 或用 Studio 自带 Gradle）生成 Wrapper 后使用 **`./gradlew`**。

## 命令行构建 APK（Debug）

在**本仓库的 `android/` 目录**（即与 `settings.gradle.kts` 同级）执行 **`assembleDebug`**。

**输出 APK（相对 `android/`）：** [`app/build/outputs/apk/debug/app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk)  

（构建前该路径不存在；成功执行 `assembleDebug` 后生成。）

### 若已有 `gradlew`（推荐）

```bash
cd android
chmod +x gradlew   # 仅首次
./gradlew assembleDebug
```

### 若无 `gradlew`，但已通过 Android Studio 同步过

Gradle **8.4** 已缓存在本机时，可先定位可执行文件再构建（**macOS / Linux 通用**）：

```bash
cd android
GRADLE_BIN=$(find "$HOME/.gradle/wrapper/dists/gradle-8.4-bin" -path '*/gradle-8.4/bin/gradle' -type f 2>/dev/null | head -1)
[ -n "$GRADLE_BIN" ] && "$GRADLE_BIN" --no-daemon assembleDebug
```

若 `find` 无结果，说明尚未下载该版本分发包，请先用 **Android Studio** 对本工程执行一次 **Gradle Sync**，或在本机安装 Gradle 后于本目录运行 **`gradle wrapper`** 生成 **`./gradlew`**。

首次构建需能访问 Maven / Google 仓库以下载依赖；失败时请检查网络与代理。

**官方参考**（可选）：Android 命令行构建总览见 [Android Studio：从命令行构建](https://developer.android.com/build/building-cmdline)。

## 使用 ADB 安装与启动（可选）

确保大屏已 **USB 调试** 或 **无线调试**（如 `adb tcpip 5555`），且电脑能访问设备 IP。工具下载：[Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)。

以下命令在 **`android/`** 目录下执行（与 [命令行构建](#命令行构建-apkdebug) 相同工作目录），以便使用相对路径安装 APK：

```bash
# 示例：macOS 上 adb 未加入 PATH 时（与上文「常见路径」一致）
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" connect 192.168.0.158:5555
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.skxf.display/.MainActivity
```

将 **`192.168.0.158`** 换成实际大屏地址；若当前目录不是 **`android/`**，请把 `install` 的路径改为 APK 的**绝对路径**。应用 ID 与入口见 [`app/build.gradle.kts`](app/build.gradle.kts)（`applicationId`）、[`MainActivity.kt`](app/src/main/java/com/skxf/display/MainActivity.kt)。

## 使用说明（推荐流程）

1. **电脑**：在仓库根目录照常运行 **`node server.js`**（见 [**`README.md`**](../README.md)），控制台出现 **「局域网发现: UDP 39300 …」**（除非在 [**`docs/SECURITY_AUDIT.md`**](../docs/SECURITY_AUDIT.md) 所述将 **`DASHBOARD_DISCOVERY_PORT=0`**）。
2. **大屏**：安装本 APK。
3. **防火墙**：服务端机器需放行 **UDP 39300 入站**（用于接收探测并回复）。
4. 点开 App：**隐藏状态栏 / 导航条**，约 **2.6s** 内完成发现并成功则直接 **`loadUrl`**。

若路由器 **AP 隔离** / 防火墙拦截：**左上角 ⚙** 填兜底 **`http://电脑IP:3000/?mode=display`**；可勾选 **「仅用上述地址」** 跳过 UDP。

## 与后端对齐

| 项 | 说明 |
|---|------|
| 探测载荷 | **`SKXF_DISCOVER`** |
| UDP 端口 | 默认 **`39300`** — 服务端环境变量 **`DASHBOARD_DISCOVERY_PORT`**（[**`server.js`**](../server.js)）；若服务端改端口，请同步修改 [`DashboardDiscovery.kt`](app/src/main/java/com/skxf/display/DashboardDiscovery.kt) 中 **`UDP_PORT`** |
| JSON | 优先 **`suggestUrl`**，否则 **`ips`** + **`httpPort`** + **`displayPath`**（顺序与校验逻辑见 `DashboardDiscovery.kt`） |

**完整字段与语义**见 **[`plan/architecture.md`](../plan/architecture.md)**。

## Manifest 备忘

- 默认 **`landscape`**；竖屏一体机可改 `AndroidManifest.xml` 中 `screenOrientation`：[AndroidManifest.xml](app/src/main/AndroidManifest.xml)。
- **`CHANGE_WIFI_MULTICAST_STATE`**：与部分 Wi‑Fi 机型的 UDP 兼容有关，说明见 **[`plan/android.md`](../plan/android.md)**。
