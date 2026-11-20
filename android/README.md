# Leon Android 管理客户端

独立安装的 Android 应用，最低支持 Android 8.0（API 26）。原生工作台提供搜索、分组卡片、全部模块菜单和底部导航；后台功能通过应用内的 WebView 工作空间使用，保留服务端现有表单、编排画布和更新能力。它需要联网连接服务端，不提供离线业务数据副本。

默认地址为 `https://121.40.254.112`。安装 APK 后，从工作台选择模块，首次打开时在站内登录页输入现有管理访问令牌。也可先进入「设置」更换 HTTPS 地址。此应用接入现有服务，不需要为客户端额外部署服务端。

## 功能覆盖

| 入口 | 功能 |
| --- | --- |
| 后台总览 | 原管理工作空间首页 |
| Leon Tools Pro | 集合、网页、便签、待办及工具 |
| 快捷项 | 分类、编辑、搜索、JSON 导入和导出 |
| JSON 记录 | 检索、记录详情及原页面的编辑工具 |
| 工作流编排 | 节点、脚本、凭据、草稿、发布、运行与定时配置 |
| 运行实例 | 筛选、分页、实例状态和节点详情 |
| Agent 工作台 | Agent、会话、模型及用量 |
| 用户管理 | 用户、会话、背景资料与分析 |
| 歌曲管理 | 多文件上传、试听、歌单和歌曲整理 |
| 歌曲统计 | 原后台曲库和播放统计页面 |
| 服务器监控 | CPU、内存、磁盘、PV、部署记录和原有一键部署功能 |
| 数据库管理 | 表、容量统计和只读 SQL 查询 |
| 高级设置 | 模型配置、加密凭据和数据库 SQL 备份 |

入口来自 `app/src/main/assets/modules.json`。测试会将其与服务端 `workspace/app.js` 的路由逐一比对，新增后台模块后需同步更新入口表。详情页在应用内继续打开。

## 手机交互

- 「工作台」列出全部管理模块；顶部菜单可随时切换模块。切换已加载的模块复用原工作空间的路由，保留页面状态。
- 「工作流」「监控」可直接进入对应模块。Android 返回键先返回网页历史，再回到工作台。
- 页面保留原有手机布局，移除重复的网站侧栏和顶栏。工作流支持触摸操作、横屏、原页面全屏画布和缩放；设置中的桌面布局可查看更宽的内容。
- 上传使用系统文件选择器，支持多选，不申请全盘存储权限。JSON 导出和 SQL 备份使用系统「另存为」，由用户选择保存位置；SQL 直接流式写入，Blob 导出按 48 KiB 分块确认写入。
- 顶部刷新用于重新加载；网络或证书错误展示重试入口。设置可选择启动时恢复上次模块。

首次打开、刷新和恢复管理模块时，客户端统一加载受登录保护的 /index.html，再由站内路由切换到目标页面。部分页面（例如歌曲统计）虽然已在工作空间提供，但其 .html 直达路径可能未配置到 HTTPS 代理；客户端沿用受保护的工作空间入口和资源，不放宽认证或修改线上代理。HTTP 403 会明确显示访问被拒绝，区别于网络连接失败。

## 认证与边界

沿用站内登录、`Secure` / `HttpOnly` Cookie 和服务端会话撤销，不新增认证接口或放宽来源校验。客户端只持久化服务地址、显示偏好和最近管理路径，不保存原始访问令牌。业务写入仍由站内脚本携带 `Origin` 和 `X-Requested-With`，遵守服务端管理写入校验。

只接受 HTTPS 服务根地址，认证工作空间限制为准确的协议、主机与端口；外部链接交给系统浏览器。HTTPS 证书错误直接拒绝。禁用明文连接、混合内容、本地文件访问、第三方 Cookie、WebView 调试和应用备份，不添加 `addJavascriptInterface`。导出使用只传给准确服务来源的 `WebMessagePort`；原始 Cookie 只在原生下载及退出请求中传给相同 HTTPS 来源，不跟随重定向。

退出账号和更换服务器会先调用原 `/auth/logout` 撤销当前会话，成功后清理本地 Cookie 与网页存储；撤销失败会保留当前连接并提示重试。配置不同域名时，还需由服务端管理员将管理写入来源配置为该域名。

## 构建

需要 JDK 17 或 21、Android SDK platform 35 / build-tools 34.0.0，以及 Node.js 18+。项目固定使用 Gradle 8.11.1 和 Android Gradle Plugin 8.7.2，可用 Android Studio 直接打开此目录。版本兼容信息见 [Android 官方构建说明](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。

仓库根目录执行：

```bash
python3 scripts/build_android.py
# 依赖已缓存时：
python3 scripts/build_android.py --offline
```

脚本自动查找 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或常用 SDK 目录，运行管理路由及导出桥接测试、JVM 地址安全测试、Android 8 / 15 的 Robolectric 启动和交互测试、Android lint 和 Debug 构建。首次测试会下载 Robolectric 对应的平台库；使用 `--offline` 前需先完成一次测试以准备这些平台缓存。成功生成：

```text
dist/android/leon-workflow-admin-1.0.2-debug.apk
dist/android/leon-workflow-admin-1.0.2-debug.apk.sha256
```

也可手动构建：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 已由本机开发证书签名，可直接安装。正式分发请在 Android Studio 使用自己的私有签名配置生成 Release APK，并持续使用相同签名；签名文件和密码不提交版本库。常规构建不连接设备、不启动模拟器、不启动游戏，也不部署或重启服务器。

## 设备验收

当前自动测试覆盖路由完整性、HTTPS 来源隔离、恶意地址拒绝、导出文件名、消息通道能力校验、分块导出与取消，以及 Android 8 / 15 上的应用启动、原生模块搜索、地址校验、页面返回、多文件选择回调和全部 13 个入口的打开、刷新、登录回跳。完整设备验收还需要 Android 真机或模拟器及现有服务的有效管理登录：检查登录与重启保持、各模块读写、工作流触摸编排、音乐多选上传与试听、SQL / JSON 保存、横竖屏切换、返回键和退出撤销。测试不要删除已有业务数据；监控页的部署按钮会触发现有生产部署，应仅在明确需要升级服务时操作。

全入口在线巡检使用 Playwright 和 Chrome，在仓库根目录执行（按本机环境设置模块与浏览器路径）：

    PLAYWRIGHT_MODULE_PATH=/path/to/playwright CHROME_PATH=/path/to/chrome node android/tests/entries-live.cjs --live

默认连接现有正式 HTTPS 地址，访问令牌从本机 ~/.config/leon-workflow/api-token 读取；可用 LEON_ADMIN_AUDIT_URL 和 LEON_ADMIN_AUDIT_TOKEN_FILE 指定其他环境。令牌、Cookie 和业务数据只在内存中使用。巡检创建临时会话并在结束时撤销，验证每个模块的受保护资源、主要数据接口、首次打开、站内切换及客户端刷新流程，并检查 Tools Pro 的全部子页。业务写入和 PV 统计会被浏览器拦截，不触发部署、工作流执行或数据修改。

结果写入 dist/android/entry-audit.json，只记录模块名称、入口路径、HTTP 状态、检查结果和临时会话是否已撤销，不包含凭据或页面业务内容。部分页面在站内切换时会复用已加载的数据；巡检以页面正确显示为准，不要求每次切换都重新读取相同接口。
