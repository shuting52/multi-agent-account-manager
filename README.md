# 叠号（Multi-Agent Account Manager）

[![LINUX DO](https://img.shields.io/badge/Community-LINUX%20DO-0064e0?style=flat-square)](https://linux.do/)

基于 Tauri 2 构建的跨平台（macOS / Windows / Android）AI 助手多账号隔离与快速切换工具，安装后应用名称为 **叠号**。

## 支持服务与核心特性

| 服务 | 状态 | 说明 |
| --- | --- | --- |
| **Muse · Meta** | 已启用 | 独立会话隔离、邮箱识别、额度明细与额外额度同步、Space 预览 |
| **Hark** | 已启用 | 独立会话隔离、邮箱识别、每日/每月双上限额度同步 |
| **Cue** | 已启用 | 独立会话隔离、邮箱识别（本 Fork 新增；额度同步为通用解析，未做 Cue 专属额度接口适配） |
| **外部 API / CLI Agent** | 群聊可用（桌面端） | 支持接入 OpenAI 兼容 API 与本地 CLI 工具参与混合群聊 |

- **原生级多账号隔离**：macOS 使用独立 UUID `WKWebsiteDataStore`，Windows 使用独立 WebView2 `UserDataFolder`，Android 使用独立 WebView `MULTI_PROFILE`。
- **多账号群聊协作（桌面端）**：
  - 支持创建/编辑/删除群聊，接入 Muse、Hark、Cue 与外部 OpenAI 兼容 API / 本地 CLI Agent 同群协作。
  - 支持 **并行讨论**、**评审流**、**A2A 接力** 三种协作模式，支持 `@成员`、`@渠道` 与 `@全体` 点名路由；按渠道自动选号时基于剩余额度智能选择并支持失败自动冷却切换。
  - 支持 **共享上下文 & 技能（Skills）**、**成员角色设定**、**沉淀本轮结论**、**新建话题**、**站点选择器适配器自定义覆盖** 等进阶编排能力。
- **Cookie 导入登录与邮箱识别（全平台）**：
  - 在「新增账号」弹窗或「账号设置」弹窗中粘贴 **JSON 数组**、**Netscape `cookies.txt`** 或 **HTTP `Cookie:` 请求头** 即可导入对应账号的独立隔离容器（macOS / Windows / Android 均支持）。
  - 创建时导入会在打开页面前校验并写入 Cookie，直接进入已登录状态。
  - 自动从 Cookie、页面数据及 Meta 账户中心（`accountscenter.meta.com`）提取真实邮箱；若导入的 Cookie 未包含 `.meta.com` 凭据且无法读取邮箱，账号将标记为「已登录账号」并在下方展示 **「未获取到邮箱 · 手动改名」** 快捷按钮。
- **工具权限自动审批（桌面端）**：
  - 在左下角 **设置** 中开启「自动审批权限请求（前台与后台保活账号）」后，当前页与后台已预热保活账号会自动识别授权卡片/弹窗，优先点击单次授权按钮（「允许一次」「允许」「批准」等），并严格拦截删除、注销、退出登录、支付、改密等高危操作。
- **登录态校验与状态异常恢复**：
  - 主动与被动检测登录态有效性，掉线时自动清理失效会话；识别错误页或受限页并标红「状态异常」与一键 **清理重登**，群聊与自动选号会自动跳过异常账号。
- **快捷切号、托盘、代理与深色模式**：
  - 支持 `⌘1~9` / `Ctrl+1~9` 及 `⌘↑`/`⌘↓`/`⌘[`/`⌘]` 快捷切号，全局呼出/隐藏快捷键，关闭最小化到系统托盘，随系统切换深色模式。
  - 支持每个账号独立 HTTP CONNECT 代理（`http://主机:端口`）及一键测速，展示认证 Cookie 剩余有效期，一键复制当前账号 Cookie，并支持客户端内 GitHub Releases `SHA-256` 校验覆盖更新。

---

## 核心功能使用说明

### 1. 多账号群聊协作（桌面端）

1. 点击左侧栏 **「群聊」→「+ 创建群聊」**，勾选要参与的多平台账号或点击 **「API/CLI」** 添加外部模型/本地命令行工具。
2. 在顶部工具栏可设置 **共享上下文 & 技能**、编辑成员角色、**沉淀本轮结论**、开启 **新话题** 或配置 **站点适配器**。
3. 在输入框下方选择 **并行 / 评审流 / A2A 接力** 模式，输入消息并用 `@成员`、`@渠道` 或 `全体成员` 发起协作提问。

### 2. 导入 Cookie 登录（解决 Google OAuth 无法内嵌登录）

1. **两种 Cookie 导入方式的区别（Muse · Meta）**：
   - **仅导入 `muse.ai` 的 Cookie**（如复制 `Cookie:` 请求头或仅导出 `muse.ai` 单域）：可以**正常登录使用 Muse 对话、Space 与同步额度**；但由于 `muse.ai` 页面本身不返回邮箱且缺少 `.meta.com` 凭据，**无法自动识别邮箱**（Muse 设置里点「Meta 账户」也会处于未登录状态）。此时账号会显示为「已登录账号」，可在新增时填写备注名，或点击卡片下方的 **「手动改名」** 自行命名。
   - **同时导入 `muse.ai` + `meta.com` 的 Cookie（推荐）**：不仅能正常使用 Muse，应用还会**自动从 Meta 账户中心（`accountscenter.meta.com`）识别并更新真实邮箱**，点开「Meta 账户」也会保持已登录状态。
2. **多域名 Cookie 导出方法**：推荐使用 Chrome 插件 **Get cookies.txt LOCALLY**，点击 **Export All Cookies**（或在 `muse.ai` 与 `accountscenter.meta.com` 页面分别点右上角 **Copy** 后上下拼接）得到 Netscape `cookies.txt` 或 JSON 数组后直接粘贴；应用会自动过滤，只导入当前服务允许域名（`.muse.ai`、`.meta.com` 等）的 Cookie，无需担心混入其他网站。
3. **导入操作入口**：点击服务栏右上角 **「+ 新增」**，在 **「导入 Cookie（可选）」** 中粘贴后点击 **「创建并打开」** 即可直接进入已登录状态；也可在已有账号的 **「账号设置（编辑图标）」** 中粘贴并点击 **「导入 Cookie」** 覆盖更新。

---

## 界面预览

### 核心功能（桌面端）

<p align="center">
  <img src="docs/screenshots/desktop-group-chat.png" alt="多账号群聊协作（A2A 接力与外部 Agent）" width="32%" />
  <img src="docs/screenshots/desktop-cookie-import.png" alt="新增账号直接导入 Cookie" width="32%" />
  <img src="docs/screenshots/desktop-auto-approve-settings.png" alt="桌面端设置与工具权限自动审批" width="32%" />
</p>

### macOS

<p align="center">
  <img src="docs/screenshots/macos-muse.png" alt="macOS Muse 多账号管理与额度同步" width="49%" />
  <img src="docs/screenshots/macos-hark.png" alt="macOS Hark 账号与双上限额度" width="49%" />
</p>

### Windows

<p align="center">
  <img src="docs/screenshots/windows-muse.png" alt="Windows Muse 多账号管理与额度同步" width="49%" />
  <img src="docs/screenshots/windows-hark.png" alt="Windows Hark 账号与双上限额度" width="49%" />
</p>

### Android

<p align="center">
  <img src="docs/screenshots/android-muse-accounts.png" alt="Android Muse 多账号与额度" width="210" />
  <img src="docs/screenshots/android-hark-accounts.png" alt="Android Hark 账号与双上限额度" width="210" />
  <img src="docs/screenshots/android-muse-chat.png" alt="Android 独立会话隔离界面" width="210" />
  <img src="docs/screenshots/android-update-modal.png" alt="Android 应用内检查更新" width="210" />
</p>

---

## 下载与安装

前往 [GitHub Releases](https://github.com/ldjx7/multi-agent-account-manager/releases) 下载对应平台安装包：

| 平台 | 下载文件 | 架构 / 系统要求 |
| --- | --- | --- |
| macOS | `account-manager-macos-universal.dmg` | Apple Silicon & Intel（macOS 14.0+） |
| Windows | `account-manager-windows-x64-setup.exe` | x64（Windows 10 / 11） |
| Android | `account-manager-android-universal-release.apk` | 通用 ABI（Android 7.0+） |

> `account-manager-macos-universal.app.tar.gz` 仅用于 macOS 应用内自动更新，首次安装请下载 `.dmg`。

### macOS 安装注意事项

1. **拖入应用程序目录**：打开 `account-manager-macos-universal.dmg` 后，请务必将 `叠号.app` 拖入 `/Applications`（应用程序）文件夹再启动。切勿直接在 DMG 挂载镜像中运行，否则无法写入替换，应用内自动更新将改为打开解压目录提示手动替换。
2. **提示「“叠号”已损坏，无法打开」或「无法验证开发者」**：
   - 因未购买 Apple 付费开发者证书签名与公证（Notarization），macOS Gatekeeper 会对浏览器下载的应用附加隔离属性 `com.apple.quarantine`，从而触发「已损坏」拦截提示。
   - **解决方法一（推荐，终端移除隔离属性）**：

     ```bash
     xattr -rd com.apple.quarantine /Applications/叠号.app
     ```

     若提示权限不足，可使用：

     ```bash
     sudo xattr -rd com.apple.quarantine /Applications/叠号.app
     ```

   - **解决方法二（系统设置放行）**：先点击一次「取消」，随后打开「系统设置 → 隐私与安全性」，在页面底部找到被拦截提示并点击「仍要打开」。
   - 若直接打开 `.dmg` 也提示异常，可先在终端对 `.dmg` 执行一次 `xattr -d com.apple.quarantine ~/Downloads/account-manager-macos-universal.dmg`。
3. **数据保留**：账号配置保存在 `~/Library/Application Support/com.ldjx.accountmanager/`，Web 登录态保存在系统 `WKWebsiteDataStore` 中。后续覆盖安装或应用内更新均不会丢失已登录账号。

### Windows 安装注意事项

1. **SmartScreen 安全拦截（「Windows 已保护你的电脑」）**：
   - 因安装包未使用付费 EV/OV 代码签名证书签名，首次运行 `account-manager-windows-x64-setup.exe` 时 Microsoft Defender SmartScreen 可能弹出拦截窗口。
   - 点击窗口中的 **「更多信息」**，再点击 **「仍要运行」** 即可正常安装。
2. **安全软件误报与校验**：
   - 若杀毒软件误报拦截，可在 Release 页面查看 `latest.json` 中 `platforms.windows.sha256` 的官方哈希，与本地安装包核对一致后放行：

     ```powershell
     Get-FileHash .\account-manager-windows-x64-setup.exe -Algorithm SHA256
     ```

3. **WebView2 运行时依赖**：
   - 依赖系统内置的 Microsoft Edge WebView2 Runtime。Windows 11 默认已自带；部分精简版或老旧 Windows 10 若首次打开空白或无法启动，请前往 [Microsoft Edge WebView2 官方下载页](https://developer.microsoft.com/microsoft-edge/webview2/) 安装「常青独立安装程序（x64）」。
4. **安装权限与数据保留**：
   - 如安装到受系统保护目录需通过 UAC 授权。账号配置与各账号独立 WebView2 数据目录均保存在 `%APPDATA%\com.ldjx.accountmanager\`，覆盖安装或应用内更新均不会清除已有登录状态。

### Android 安装注意事项

1. **允许安装未知来源应用**：首次安装 `.apk` 以及使用应用内更新时，系统会要求授予「允许安装未知来源应用」权限，授权后返回即可调起系统安装器覆盖更新。
2. **WebView 内核要求**：多账号隔离依赖 Android System WebView 的 `MULTI_PROFILE` 能力；如提示不支持，请在应用商店或系统更新中升级 Android System WebView（或 Chrome）。
3. **签名一致说明**：所有 Release APK 均使用固定发布密钥签名，可直接覆盖升级并完整保留多账号登录态。

---

## 应用内更新与发版

- **客户端检查更新**：桌面端点击左侧栏底部「更新」按钮，Android 端点击右上角下载图标，可检查最新版本并一键下载校验安装；启动后也会静默检查一次并展示红点提醒。
- **维护者发布新版本**：
  1. 同步更新 `package.json`、`package-lock.json`、`src-tauri/Cargo.toml`、`src-tauri/Cargo.lock` 和 `src-tauri/tauri.conf.json` 中的应用版本号。
  2. 提交并推送 `v*` 标签（GitHub Actions 将自动编译三端安装包、生成 `latest.json` 并创建 Release）：

     ```bash
     git tag v0.1.1
     git push origin v0.1.1
     ```

---

## 本地开发

环境要求：Node.js 22+、Rust 1.85+，Android 构建需额外配置 Android SDK 与 NDK 26+。

```bash
npm ci
npm run tauri -- dev
npm run tauri -- build
```

---

## 社区与开源协议

本项目已加入并致谢 [LINUX DO](https://linux.do/) 开源社区。

本项目基于 [Apache-2.0](LICENSE) 协议开源。
