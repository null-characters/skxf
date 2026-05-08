# 大屏看板：部署与 Android 发布屏方案

本目录说明 **skxf-1** 项目在局域网内的推荐用法：**服务端保持 DHCP**、**Android 用 WebView + UDP 发现** 打开显示页，避免反复在触屏设备上开浏览器、记 IP。

| 文档 | 内容 |
|------|------|
| [architecture.md](./architecture.md) | 服务端能力、数据流、UDP 发现协议、环境变量与安全边界 |
| [android.md](./android.md) | Android 工程要点、权限、明文 HTTP、WebView、发现逻辑与示例代码 |
| [**`../android/README.md`**](../android/README.md) | **构建与环境**：JDK/SDK、Gradle 8.4、命令行 **`assembleDebug`**、APK 路径、**`adb`** 安装与启动 |

代码仍以仓库根目录 [**`server.js`**](../server.js)、[**`public/index.html`**](../public/index.html) 为准；协议细节如有变更请同步更新本文档。项目总览与文档索引见 [**`README.md`**](../README.md)。
