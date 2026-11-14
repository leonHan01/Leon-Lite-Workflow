# Leon Lite Workflow

Java 21 / Spring Boot 的本机 Alfred 工作流服务。兼容原项目的 `/alfred/query` 协议，并继续提供 `/hello`。默认只监听 `127.0.0.1:8080`。

管理首页：<http://127.0.0.1:8080/>

## 功能

在 Alfred 中可以直接使用：

- **帮助**：`help` 或 `帮助` 列出全部能力。后面可以加关键词，例如 `help 日历`、`help 翻译`。回车复制示例命令
- **快捷项、书签、配置**：`kv-` 配置和 `WebUrlDiamond` 书签
- **Leon Tools Pro 链接**：按网页或工具名称、网址、所属集合、分组、备注检索；多个关键词可组合。结果来自当前库，修改或删除后立即生效，回车打开网页
- **时间**：`now`、`time`、`时间`、`shijian` 返回当前北京时间及秒级、毫秒级时间戳，后面可以接 `+1d`、`-30m` 这类偏移。输入 10 位秒级或 13 位毫秒级 Unix 时间戳（例如 `1735689600`），显示并可复制北京时间与 UTC，精确到秒。输入北京时间 `2026-10-31 12:00:00`，可复制对应的秒级和毫秒级时间戳
- **日历**：`cal` 或 `日历` 输出当月日历，一行一周，周一开头。今天用方括号标出，最后一行是今天的日期
- **Markdown**：`md` 列出常用语法和原有 callout，`md 表格`、`md 代码`、`md 链接` 按关键词筛选，回车复制
- **计算器、代码片段、LeetCode 名称**
- **JSON 记录**：输入 JSON 会保存记录并返回 `/json-record.html?id=...`，可在 `/json-record-search.html` 搜索
- **有道翻译**：`fy 内容`。需要 `YOUDAO_APP_KEY` 和 `YOUDAO_APP_SECRET`
- **重启**：`restart` 只提示改用服务管理工具。原服务器专用脚本未迁移

Leon Tools Pro 插件继续走原来的 `/api`。见 [插件接口](#插件接口)。

## 本地启动

需要 Java 21 和 Maven。macOS 上选择 JDK 后启动：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

H2 把数据写到运行目录下的 `data/leon-workflow.mv.db`。该目录不提交 Git。首次启动自动建表。更换工作目录前先确认文件位置；停止服务后备份整个 `data` 目录。

`LEON_DB_PASSWORD` 设置的是数据库用户密码，文件本身不加密。

## 环境变量

两项认证配置都未设置时，本机开发保持无认证。此时管理接口没有登录保护，不要把服务直接暴露到不可信网络。

| 变量 | 作用 |
| --- | --- |
| `LEON_DB_URL` | 覆盖 JDBC 地址，例如 `jdbc:h2:file:/绝对路径/leon-workflow` |
| `LEON_DB_PASSWORD` | H2 用户 `sa` 的密码 |
| `LEON_ALFRED_API_KEY` | 设置后校验 Alfred 请求签名；留空则跳过 |
| `LEON_ALFRED_ICONS_DIR` | Alfred workflow 的 `icons` 目录。默认是当前用户的 `~/Library/Application Support/Alfred/Alfred.alfredpreferences/workflows/leon_workflow/icons` |
| `YOUDAO_APP_KEY` / `YOUDAO_APP_SECRET` | 有道翻译。真实凭据不要写入源码 |
| `LEON_AUTH_TOKEN_SHA256` | 访问令牌 UTF-8 字节的 SHA-256 十六进制摘要。设置合法摘要后即启用令牌认证 |
| `LEON_AUTH_REQUIRED` | 设为 `true` 时，摘要缺失或格式错误会拒绝启动 |
| `LEON_AUTH_ADMIN_ORIGIN` | 管理页写请求必须匹配的准确 `Origin` |
| `LEON_MUSIC_DIRECTORY` | 歌曲音频目录，默认 `./data/music`。正式部署应设为 `/var/lib/leon-lite-workflow/music` 并给予服务用户读写权限 |
| `LEON_PLUGIN_DIGEST_SCHEDULING_ENABLED` | `true` 时启用定时统计和日结，默认 `false` |
| `PLAYWRIGHT_MODULE_PATH` / `CHROME_PATH` | 浏览器回归测试使用的本机依赖 |

正式环境的有道密钥放在 `/etc/leon-lite-workflow-youdao.env`（root 所有，权限 `0600`），由 systemd 的 `97-youdao.conf` 加载。升级时保留该文件。检查时只确认变量已经设置，不要输出文件内容或进程的完整环境。

## Alfred

`POST /alfred/query` 的请求体：

```json
{"param":"1+2","key":"..."}
```

响应是原项目格式的结果数组。设置 `LEON_ALFRED_API_KEY` 后，`key` 为「原样 `param` 与密钥拼接」的 UTF-8 MD5，小写十六进制，与原客户端签名方式一致。`GET /alfred/statistics` 为兼容保留。

启用令牌认证后，访问 `/alfred/**` 还要带 `Authorization: Bearer <令牌>`。签名和令牌分别校验。

快捷项在 `/simple-kv.html` 管理。`all` / `all2` 分页；带「首页」动作的项出现在空查询结果中。

`kv-` 开头的配置按行解析，并可以设置 `defaultIcon` 和 `defaultTags`：

```text
名称:内容<网址>[标签,标签]{paste}
```

`WebUrlDiamond` 使用原书签 JSON 数组。示例：

```bash
curl -X POST http://127.0.0.1:8080/api/diamond -H 'Content-Type: application/json' \
  -d '{"diamondKey":"kv-demo","diamondValue":"示例:内容[标签]"}'
curl -X POST http://127.0.0.1:8080/api/diamond -H 'Content-Type: application/json' \
  -d '{"diamondKey":"WebUrlDiamond","diamondValue":"[{\"name\":\"示例 标签\",\"url\":\"https://example.com\"}]"}'
```

图标方面，服务内置一组与现有 Alfred workflow 同名的 PNG，并合并 `LEON_ALFRED_ICONS_DIR` 中的文件。同名时目录里的文件优先。管理页可以预览内置图标。Alfred 在客户端读取 workflow 的 `icons` 目录；其他客户端需要把所选 PNG 放入该目录才能显示。

## 管理页面

管理页面共用一个工作空间外壳。站内切换通过 History API 和 `workspace/app.js` 更新视图，侧边栏和已加载表单留在当前页。刷新和直接打开原地址仍然可用。各视图的内容、事件和样式放在 `static/workspace/`，避免全局变量和样式互相影响。

| 路径 | 内容 |
| --- | --- |
| `/` | 总览 |
| `/simple-kv.html` | 快捷项 |
| `/workflows.html` | 可视化工作流编排、定时配置、双语言脚本和运行记录 |
| `/workflow-runs.html` | 全部工作流的运行实例、筛选分页和节点详情 |
| `/json-record-search.html`、`/json-record.html` | JSON 记录与详情 |
| `/monitor.html` | 服务器监控 |
| `/music.html` | 声屿歌曲管理，支持多首上传、搜索、试听和删除 |
| `/advanced-settings.html` | 高级设置，含数据库 SQL 备份 |
| `/admin-login.html` | 访问令牌登录 |

**备份。** 高级设置下载的 SQL 包含 H2 表结构和业务数据，可导入空的 H2 数据库。其中不含数据库用户密码、浏览器登录会话、环境变量和服务器配置。备份可能包含私人内容，以及应用数据中的密钥，请单独保管。

**监控。** 侧栏打开 `/monitor.html`。每分钟采集系统 CPU、物理内存（总量减空闲量）和根文件系统磁盘使用率，内存中最多保留 24 小时，服务重启后重新积累；指标不可用时显示「—」。PV 统计工作空间页面的首次打开、刷新和站内切换，按北京时间把每日汇总写入 H2，从启用之后开始记录，不包括 API、Alfred 和插件请求。图表使用随服务提供的 ECharts。监控接口沿用现有认证，以及管理写入的来源限制。

**部署记录。** `/monitor.html` 默认展示最近 5 次部署，可折叠或展开查看最近 50 次。记录包含北京时间、版本、Git 提交号与提交说明、工作区是否包含未提交改动、部署说明、结果和耗时，并标记当前实际运行版本；未保存提交说明的历史记录显示「未记录」。成功、失败、部署中及回滚均有独立状态，历史文件持久化保存，普通重启不产生重复记录。旧版已保留的 `release.json` 可补录为成功记录，时间取原校验完成时间，未知耗时显示「—」。

正式部署运行 `python3 scripts/deploy.py --description '本次部署说明'`。脚本使用 Java 21 完整构建和测试，通过 Workbench 上传并校验 SHA-256，备份旧程序和 H2，保留配置后重启服务，再使用已有 Alfred 本地令牌验证公网 HTTPS、认证拦截、授权查询及部署记录。验证失败恢复旧程序；不自动回退数据库或清除业务数据。记录位于 `/var/lib/leon-lite-workflow/deployments/`，由 `LEON_DEPLOYMENTS_DIRECTORY` 指定；`LEON_DEPLOYMENT_ID` 标识当前运行部署。时间版本号使用 `yyyy.MM.dd.HHmmss`，精确程序包由提交号、工作区状态和 SHA-256 区分。备份服务器数据时同时备份该目录，H2 SQL 导出不包含这些部署记录。

**部署备份保留。** 命令行和网页部署均在成功完成验证后，保留 `/opt/leon-lite-workflow/backups/` 下最近 **2 次部署的完整备份**（旧 JAR、停止服务后的 H2 快照、部署配置及校验信息），自动清理更早的备份和对应构建目录。新快照写入文件校验清单，清理前核验 JAR、数据库快照及回滚配置；不足 2 份有效备份或本次回滚包不完整时不清理。失败或正在回滚的部署不会触发清理，正在构建、上传及无关目录也会保留。

工作区上传目录统一位于 `/opt/leon-lite-workflow/staging/`，与部署后台共享可见；旧 `/tmp/leon-release-*` 上传目录会在成功部署后迁移清理，未完成上传的临时目录保留至少 24 小时。Maven 依赖缓存和 Git 仓库复用，不按部署删除。部署历史记录、正式 H2 数据、音乐音频及私有配置不参与清理。

**网页一键部署。** 管理页登录后，在监控页点击「一键部署」，后台从 `https://github.com/leonHan01/Leon-Lite-Workflow.git` 的 `main` 分支拉取代码，以独立的 `leon-workflow-build` 账户运行 Java 21 / Maven `clean package`（跳过测试执行），完成备份后更新正式服务。Git 协议受限时，通过 GitHub 官方 API 解析 main 提交号，再下载该提交的官方源码归档，全程验证 HTTPS，不使用第三方镜像。页面自动显示排队、拉取、构建、更新、验证及最终结果，部署期间禁止重复触发。构建失败不替换当前服务，部署或验证失败恢复旧程序，保留数据库及私有配置。若远程分支尚未包含部署控制代码，会在替换服务前拒绝发布，避免移除管理功能。

`POST /api/monitor/deploy` 仅接受已登录管理页的 Cookie、准确的 `Origin` 和 `X-Requested-With`，插件 Bearer 令牌不能直接触发部署；客户端不能指定仓库、分支或命令。请求通过持久化目录通知 `leon-workflow-deploy.path`，由独立的 `leon-workflow-deploy.service` 执行 `/opt/leon-workflow-deploy/` 中 root 所有的固定脚本，应用本身不获得任意 sudo 权限。构建日志仅保存在 root 可读的 `/var/log/leon-workflow-deploy/`，页面只展示固定进度信息。新程序使用临时会话验证公网认证和授权 Alfred 查询，会话用完立即撤销，不向构建进程传递应用凭据。首次安装通过 Workbench 上传并校验脚本和 Maven 包，再运行 `scripts/install_deploy_worker.py --staging <临时目录>`；保留已有代理及配置，启用 `LEON_DEPLOY_ENABLED` 和 `LEON_DEPLOY_SELF_VERIFY`。后台被中断时，由 `ExecStopPost` 恢复并记录结果。

**登录。** 管理页与插件、Alfred 使用同一个访问令牌。默认勾选「永久记住登录」：服务端会话不设到期时间；浏览器通过 `Secure`、`HttpOnly`、`SameSite=Lax` 的 Cookie 保存随机会话凭证，关闭浏览器后仍然有效。持久 Cookie 为 400 天，调用管理接口时自动续期。浏览器可能限制 Cookie 的保存时间，或清除 Cookie。取消勾选时使用浏览器会话 Cookie，服务端最长保留 12 小时。原始令牌不写入 localStorage 或 sessionStorage；服务端保存会话摘要和有效期。会话保存在 H2 中，升级重启不会丢失。主动退出、清除 Cookie 或更换访问令牌后需要重新登录。升级前创建的 30 天会话保持原有效期，重新登录后采用新设置。共享设备使用后点击右上角「退出登录」。

部署时，将 `/workspace/` 静态资源与其它管理页面放在同一套 HTTPS 登录保护之后。

### 声屿在线歌曲

在 `/music.html` 上传 MP3、M4A、AAC、WAV、AIFF、CAF 或 FLAC，单首最多 128 MB，多首依次上传。歌曲名默认取文件名，艺术家和专辑可在上传时填写。浏览器读取可识别格式的时长；声屿客户端下载后补充音频标签、封面和实际时长。管理页提供搜索、试听及确认后删除。删除同时移除服务器音频文件，客户端已缓存的离线副本保留。

在线歌单由服务端保存，Mac 与 Android 客户端每次刷新曲库时同步。管理页曲库按 10 / 20 / 50 条分页，默认 10 条并记住每页数量；搜索和切换歌单回到第一页，跨页选择保留，全选只作用于当前页。分页仅限制管理页显示，不改变客户端歌曲接口及完整歌单顺序。页面使用紧凑导航与自适应宽度。管理页左侧歌单卡片支持拖放：拖动一首歌曲，或先勾选多首再拖动其中任意一首，松开到歌单即可加入。新建歌单后继续展示全部曲库；拖动时目标高亮，保存后更新数量并跳过已有歌曲，失败时保留选择以便重试。上传面板可展开收起，空曲库默认展开；键盘和触屏仍可使用勾选后批量加入。在管理页创建或重命名歌单；上传时可指定歌单，也可在曲库勾选歌曲后批量加入目标歌单。一首歌可属于多张歌单。查看某张歌单后可移出歌曲；删除歌单只删除归属，不删除曲库音频。删除歌曲会自动清理所有歌单中的引用。

管理页选中具体歌单后，可将歌曲拖到其他曲目上方或下方排序，也可使用 ↑ ↓ 微调。多选曲目会按原有相对顺序整体移动；搜索时保留未显示的歌曲。松开后自动保存至 H2，保存失败保持原顺序；并发修改返回 409 并刷新，不覆盖其他设备的新顺序。客户端刷新后重新播放歌单，按新顺序建立队列。

客户端在「在线歌单」中选择具体歌单，点击播放全部或某首歌曲，按歌单顺序建立队列。断网后保留上次歌单列表，只有已缓存的歌曲可以播放。在线歌单和各设备原有本地歌单分别保存，沿用同一套 token 权限。

接口沿用现有认证：客户端使用 Bearer 令牌，管理页使用 HttpOnly Cookie；Cookie 写请求仍验证准确 Origin 和 `X-Requested-With`，不会公开未认证的音乐接口。

| 接口 | 行为 |
| --- | --- |
| `GET /api/music/tracks` | 返回歌曲 JSON 数组，含稳定 UUID、标题、艺术家、专辑、格式、时长、字节数、SHA-256 和上传时间 |
| `GET /api/music/tracks/radio?exclude=<UUID>` | 电台随机返回一首有音频文件的歌曲。多首时排除上一首，只有一首可播放时可重复；无可播放歌曲返回 204。沿用现有认证及 no-store |
| `POST /api/music/tracks` | multipart 上传单个 `file`，可附带 `title`、`artist`、`album`、`duration`；成功返回 201 |
| `PATCH /api/music/tracks/metadata` | JSON `{ "ids": ["UUID"], "title": "歌名", "artist": "歌手", "album": "专辑" }`；最多 100 首，字段可省略，整批事务提交。修改的是服务端记录，不改音频标签。 |
| `POST /api/music/tracks/{id}/artwork` | multipart `file` 上传 JPEG 或 PNG（不超过 2 MiB，最多 1600 万像素），保存在服务端数据库，不改音频。 |
| `DELETE /api/music/tracks/{id}/artwork` | 移除自定义封面，恢复读取音频内嵌封面。 |
| `GET /api/music/tracks/{id}/audio` | 认证后下载音频，支持 Range 请求和浏览器试听 |
| `DELETE /api/music/tracks/{id}` | 删除元数据及服务器文件，成功返回 204 |
| `GET /api/music/playlists` | 返回在线歌单数组，每张包含 UUID、名称、有序 `trackIds` 和创建/更新时间 |
| `GET /api/music/playlists/{id}` | 查看一张在线歌单 |
| `POST /api/music/playlists` | JSON `{ "name": "通勤" }`，创建歌单并返回 201 |
| `PUT /api/music/playlists/{id}` | JSON `{ "name": "新名称" }`，重命名歌单 |
| `POST /api/music/playlists/{id}/tracks` | JSON `{ "trackIds": ["UUID"] }`，按顺序批量加入，自动去重；单次最多 1000 首 |
| `DELETE /api/music/playlists/{id}/tracks/{trackId}` | 仅移出歌单，返回更新后的歌单 |
| `PUT /api/music/playlists/{id}/order` | JSON `{ "trackIds": ["新完整顺序"], "expectedTrackIds": ["原完整顺序"] }`；只改位置，要求成员完全相同且不重复，原顺序已变化返回 409，成功返回更新后歌单 |
| `DELETE /api/music/playlists/{id}` | 仅删除歌单及归属，返回 204 |

原生客户端上传时使用 `POST /api/music/tracks?reuseExisting=true`；服务端按 SHA-256、大小及格式复用已有可播放音频，默认普通上传仍创建独立歌曲。创建歌单可传入可选 `id`（UUID），重复请求返回已有歌单；客户端上传后按本地顺序调用完整顺序接口，保留云端额外加入的歌曲。

管理页支持把 MP3 等音频直接拖到页面自动上传，多首依次处理；拖到歌单卡片时上传后自动加入该歌单，文件类型、非空及 128 MB 限制在客户端预检。上传状态与单首失败会显示，已有歌曲排序拖动保持独立。点击播放后显示试听播放器，播放器随窗口宽度扩展为 360–640px，便于拖动进度。

管理页移除顶部宣传区，曲库工具栏集中标题、搜索与刷新；使用左侧卡片查看歌单，选中歌单才显示重命名及删除，勾选歌曲后才显示批量加入操作，可一键取消选择。保留分页、跨页勾选和拖拽排序。

删除歌曲会清理所有在线歌单引用；原生客户端下一次成功拉取完整曲库后清理对应本机缓存，离线设备联网后同步。本地原文件独立保留。删除先完成数据库约束检查，再清理音频，存储失败时回滚歌曲与歌单引用，返回 503。

歌曲与在线歌单元数据保存在 H2 的 `music_track`、`music_playlist`、`music_playlist_item` 表，音频使用 UUID 文件名保存在 `LEON_MUSIC_DIRECTORY`。上传验证后缀、文件头、大小和元数据；文件先写临时路径，校验成功后原子移动，数据库保存失败时清理文件。SQL 备份包含歌曲元数据，**不包含音频文件**，恢复时同时恢复对应音频目录。

上线时在现有 HTTPS 代理保护下加入 `/music.html`，并允许已认证的 `/api/music/**`。反向代理请求体限制至少为 130 MB，上传超时应覆盖大文件传输。保留现有 Cookie 登录、内部 `/auth/check`、Origin 校验及数据库目录。客户端默认地址仍是 `https://121.40.254.112`，无需调整公网端口。

新增 `MusicIntegrationTest` 验证上传、列表、Range 下载、删除、认证、写入来源及坏文件拒绝；`src/test/browser/music-management.cjs` 使用本机模拟数据验证管理页上传、搜索、删除及窄屏布局。

## 认证与公网

`LEON_AUTH_TOKEN_SHA256` 为合法的 64 位十六进制摘要时，令牌认证生效。`LEON_AUTH_REQUIRED=true` 且摘要缺失或格式错误时，服务拒绝启动。两者都未设置时，保持本机无认证。

插件和 Alfred 以 `Authorization: Bearer <令牌>` 访问 `/api/**` 和 `/alfred/**`。令牌缺失或错误返回 401。

公网必须使用 HTTPS，并在反向代理上限制公开路径。令牌、摘要和敏感配置不要写入日志或版本库。

HTTPS 代理通过内部 `/auth/check` 校验管理页面和静态资源的会话，只公开登录和退出接口。管理接口同时接受 Cookie 和 Bearer。Cookie 以及兼容的 Basic 写请求，必须来自 `LEON_AUTH_ADMIN_ORIGIN` 指定的准确来源，并携带 `X-Requested-With: XMLHttpRequest`。公网 Cookie 只通过 HTTPS 发送。页面返回 `Cache-Control: no-store`。浏览器缓存不是登录凭证。

插件接口默认允许 Chrome 扩展和本机网页跨域访问，包括读取插件设置中的敏感值。请只在可信的本机环境使用。

## 插件接口

保留原插件的网页集合、分组和网页、便签、待办、工具箱、LLM 会话与消息、插件设置、待办统计。`/api` 路径、数字 ID、响应包装和逻辑删除行为不变。默认地址 `http://127.0.0.1:8080` 与插件现有默认值一致。

新 H2 库中的插件表初始为空。**未完成 ID 保留迁移及核对前，不要将已有插件切换到这个库**，否则缓存中的数字 ID 可能指向错误记录。

定时统计和日结默认关闭。确认数据及配置迁移完成，并停止旧后端对应定时任务后，设置 `LEON_PLUGIN_DIGEST_SCHEDULING_ENABLED=true`：

- 北京时间 10:00、14:00、20:00 发送统计
- 北京时间 00:00 生成日结；DeepSeek 失败时回退为原始统计

手动统计和日结接口不受此开关限制。普通聊天和即时通知仍由插件负责。设置值保存在数据库中，不要把密钥写入日志或文档。

## 数据迁移

本次迁移代码和表结构，不会读取或自动导入原 MySQL 的个人数据。新库初始为空。原 MySQL 的连接、表和数据不会被修改。H2 与 MySQL 的 SQL 方言不同，MySQL 整库转储不能直接在 H2 上执行。

### 工作流表

需要原快捷项、配置或 JSON 历史时，先备份原库，再将 `simple_kv`、`leon_diamond`、`json_record` 按对应字段导入新库。迁移后重启服务以刷新缓存。

### 插件表

以下为操作方案。**备份、导入和切换均未执行。** 动手前确认当前源 MySQL 实例、目标 H2 文件、两边实际表结构、数据量，以及可用停机窗口。

已对源项目默认配置指向的 MySQL 做过只读元数据检查：九张插件表的列与迁入实体对应。MySQL 的 `BIT`、`DATETIME` 和 `TEXT` 需要分别与目标 H2 的布尔、时间和文本类型核对。其他 profile 或实际运行实例，仍要在迁移前确认。

1. 停止插件写入，以及两套服务的定时任务。分别备份源 MySQL 和停服后的目标 H2 文件，并验证备份可以恢复。保留浏览器缓存及未同步数据。
2. 在隔离的目标库副本中检查 `web_collection`、`web_collection_group`、`web_collection_item`、`sticky_note`、`todo_item`、`toolbox_item`、`llm_session`、`llm_message` 和 `app_setting` 的实际列、类型、约束和目标数据。源库仅以只读方式导出。
3. 逐表按字段转换，保留所有原数字 ID、引用、逻辑删除标记、顺序及时间，包含已删除记录。`app_setting` 中的密钥单独处理。目标插件表已有记录，或出现同 ID 冲突时停止，不覆盖、不重新分配 ID。先制定并验证可以同时更新所有客户端缓存及旧数据引用的映射方案，或使用无冲突的独立目标库。导入后，将每张数字 ID 表的 H2 identity 下一个值设为高于该表最大 ID。
4. 对比每表行数、ID 集合、删除状态、父子引用、排序和时间。抽查集合与会话的完整嵌套结果，验证新建 ID 不冲突。用测试配置验证设置和通知，避免打印真实设置值。原库中的孤儿消息先按源服务行为评估处理，避免额外创建会话改变预期。
5. 在副本上验证插件缓存、旧数据同步、断线失败及 CRUD。确认通过后，用备份和验证过的目标库切换服务。旧服务只保留作回滚，避免双写。确认新端正常后，再选择启用新端定时任务。回滚时停止新端任务，恢复切换前的数据和连接配置，并先处理切换后的新增写入。

## 开发约定

### 工作流编排（一期）

在管理页侧栏打开「工作流编排」，新建流程自带且仅有一个「开始」和一个「结束」节点，默认直接连通，两者不能删除。可在中间添加 HTTP 请求、JavaScript、Groovy 或 IF 节点。拖动节点标题调整位置；点击右侧出口和目标节点左侧入口连线，也可在节点配置的「连接出口」中选择下一个节点。IF 分别配置 true / false 出口。开始节点不能有入口，结束节点不能有出口；发布时所有路径（包括 IF 的 true / false）必须连接到同一个结束节点，结束节点接收的数据作为最终输出。一期支持单起点、无环流程和条件分支，最多 65 个节点（含开始和结束，为原有 64 节点流程预留结束节点）；仅结束节点支持分支汇入，不支持处理节点汇合、循环、并行或 Webhook；定时执行在工作流配置中启用。

「保存草稿」允许保存尚未连接完整、配置尚未填写的流程，但必须保留且仅有一个开始和结束节点；「发布版本」先保存再校验连线、节点和配置，生成不可变版本。「运行 vN」始终执行该发布版本，未发布的编辑不参与运行。原有流程读取时将手动触发节点转换为开始节点，并为未连接出口补上结束节点；保留原有节点 ID、配置、发布版本及历史执行记录，保存草稿时持久化新结构。下方可以填写手动输入、调试当前草稿节点、查看最近 50 次运行的节点输入、输出、日志、错误和耗时，也可以停止运行。「载入双语言示例」提供一条可直接发布执行的示例：120 → JavaScript 加 5 → Groovy 翻倍 → IF 判断 → 250。

编排画布默认提供更大的可视高度和 2400 × 1600 的初始范围，节点靠近边缘时自动扩展，也可点击「扩展画布」继续增加范围。工具栏支持 5%–200% 缩放、「适应视图」、100% 重置、全屏编辑，以及收起节点库和配置面板；拖拽空白处平移，Ctrl / ⌘ + 滚轮以鼠标位置缩放。全屏模式自动收起两侧面板，点击节点可展开配置，Esc 或「退出全屏」返回。部署完成后，监控页提供「刷新界面，加载最新功能」入口，完整加载新菜单；切换页签只更新内容区域。

**定时执行。** 在工作流右上方点击「定时执行」，选择每分钟、每 5 / 15 分钟、每小时、每天 09:00、工作日 09:00，或填写六段 Cron（秒、分、时、日、月、周）。默认时区为 `Asia/Shanghai`，可使用其他 IANA 时区，预览会显示该时区下接下来 5 次执行时间。先发布工作流才能启用，启用后每次执行当时最新的已发布版本；草稿编辑不参与定时运行。定时输入单独保存，首次配置默认复制当前手动运行输入；凭据继续使用 HTTP 节点的凭据引用。

定时配置保存在 H2，服务重启后保留。同一工作流已有排队或运行实例时，跳过本次定时执行点；暂停只停止后续触发，已有实例继续运行。服务停机错过的执行点不补跑，恢复后从下一个时间点开始。轮询使用独立线程，每秒检查一次；创建实例与推进下次执行时间在一个数据库事务内完成，防止并发轮询重复创建实例。队列已满时实例标记失败，不立即重试。`LEON_WORKFLOW_SCHEDULING_ENABLED=false` 可暂停整个工作流调度循环，默认启用；它不会启用原来独立开关控制的待办统计或日结任务。

**运行实例。** 从侧栏打开「运行实例」，按工作流、状态、手动 / 定时触发、实例 ID 或名称筛选，支持每页 20 / 50 / 100 条。页面展示版本、开始时间、耗时、计划执行时间和统计汇总，默认每 4 秒刷新；查看详情可以展开节点输入、输出、日志和错误，并停止排队或运行中的实例。已删除工作流的历史实例继续保留，列表接口不返回输入输出或脚本源码，详情沿用原有脱敏规则。

两种脚本共用 JSON 协议：输入、返回值都为 `{"items":[{"json":{...}}]}`。JavaScript 入口为 `function main(input, ctx)`，Groovy 为 `def main(Map input, Map ctx)`；`ctx` 仅包含 `runId`、`nodeId` 和节点参数 `params`。切换语言分别保留源码。JavaScript 使用 GraalJS 25.0.2，Groovy 使用项目 Maven 管理的版本。脚本不是 Node.js 环境，不提供 npm、文件或网络能力；HTTP 调用使用专门的节点。HTTP 返回 `items[0].json` 包含 `status`、`headers` 和 `body`。配置字段支持 `{{ input.items[0].json.name }}`、`{{ ctx.params.name }}` 形式的字段引用，不执行任意表达式。

脚本通过独立 Java 21 子进程运行，只传递 JSON 数据，并清空主应用环境变量。macOS 使用系统 `sandbox-exec`，Linux 必须安装 `/usr/bin/bwrap`（bubblewrap）和 `/usr/bin/setpriv`（util-linux），且允许非特权用户命名空间。脚本启动链先清除继承的进程能力，兼容主服务绑定低端口的权限；发行版 JDK 链接到目录外的时区及安全策略资源按文件只读挂载。沙箱只提供只读语言运行时、JDK 和必要系统库，禁止宿主业务文件读取及网络访问；JavaScript 额外关闭 Java 主机访问。隔离环境缺失或不可用时拒绝执行脚本，不回退到主进程执行。脚本 JVM 堆上限 128 MB，元空间 96 MB，直接内存 16 MB；单节点超时 100–30000 ms（默认 10 秒，包含编译启动），工作流最长约 5 分钟，最多同时执行 2 个任务、排队 8 个。超时和取消会终止子进程及其后代。操作系统沙箱隔离访问权限，JVM 内存限制不等于整个进程或进程树的硬内存限额；部署到共享或不可信多租户环境前还应通过 cgroup / systemd 施加进程树资源配额。

HTTP 节点仅接受 HTTP/HTTPS；默认阻止回环、内网、链路本地、云元数据及其他保留地址。连接时使用校验过的 DNS 地址，防止二次解析绕过限制；不自动重试、不跟随重定向。确需调用内部系统时，由运维通过 `LEON_WORKFLOW_HTTP_ALLOWED_ORIGINS` 明确配置允许的来源，例如 `http://127.0.0.1:9000`；多个来源用逗号分隔，准确匹配协议、主机和端口，不在节点中开放绕过选项。输入、输出和 HTTP 响应最大 1 MB、最多 1000 个 item；日志最多 16 KB。

「管理凭据」支持 Bearer、Basic 和自定义 Header。启用前在应用私有环境配置中设置 `LEON_WORKFLOW_CREDENTIAL_KEY`，值为独立生成的 32 字节随机密钥的 Base64 编码。不要把密钥写进仓库、日志或网页存储，升级保留该配置；更换密钥前需安排凭据重新加密，不能直接替换。密钥不设置时仍可使用无凭据 HTTP 节点，凭据保存及读取会拒绝。凭据在 H2 中使用 AES-256-GCM 加密，接口只返回名称、类型和 ID，发送凭据必须使用 HTTPS。运行记录会隐藏常见敏感字段及已知凭据的回显，脚本和配置中应只引用凭据 ID。SQL 备份包含加密后的凭据记录，恢复后还需原有服务器加密密钥。

正式 ECS 的凭据主密钥保存在 `/etc/leon-lite-workflow-workflows.env`（root 所有、`0600`），由 `96-workflows.conf` 加载。`scripts/install_workflow_runtime.py` 会保留已有密钥、检查或安装 bubblewrap，并备份后补充受登录保护的 HTTPS 路由；它不单独重启应用。随后统一运行 `python3 scripts/deploy.py --description ...`，成功标记和备份清理前，会在线执行使用加密凭据的 HTTP → JavaScript → Groovy 流程，还会验证真实定时触发和独立实例列表，同时验证原有数据 ID 保留。验收只清理本次创建的测试流程、凭据与临时会话。

新增接口统一位于 `/api/workflows/**`，正式环境只允许管理页 Cookie 会话访问；写入还必须通过现有的准确 Origin 和 `X-Requested-With` 校验。插件/Alfred 的 Bearer 令牌不能直接管理或运行工作流，原有接口认证规则不变。页面和 `/workspace/` 资源沿用 HTTPS 代理的登录保护。

数据保存在原有 H2 内新增的 `automation_workflow`、`automation_version`、`automation_run`、`automation_node_run`、`automation_credential`、`automation_schedule` 表，不替换现有数据。节点开始和结束分别持久化状态；正常关闭服务会取消任务，异常重启会将遗留未完成任务标为失败，均不自动重放。历史实例新增触发方式和计划时间字段，旧实例默认标记为手动触发，已有 ID、版本、输入输出保留。HTTP 已发出、结果尚未保存时不能断言对端是否成功，取消也不能撤销已发送的请求；请确认对端结果或使用业务幂等键后再手动重跑。删除工作流使用逻辑删除并保留历史，执行中的流程须先停止。

工作流后端测试为 `WorkflowIntegrationTest`、`ScriptProcessRunnerTest`；浏览器测试为 `src/test/browser/workflows.cjs`，沿用 `PLAYWRIGHT_MODULE_PATH` 和 `CHROME_PATH`，验证新增节点、双语言源码保留、拖动、发布执行、分支状态、凭据及手机布局，不启动游戏。

### 现有业务模块

控制器负责 HTTP 参数、状态码和兼容响应。快捷项写入由 `SimpleKVService` 处理，配置写入及校验由 `DiamondService` 处理，图标文件读取由 `AlfredIconService` 处理。依赖通过构造器注入。

- `AlfredRequestGuard` 校验原始输入的签名，并维护全局令牌桶。`AlfredQueryService` 负责处理器排序、有效结果短路、兜底及结果字段补齐。处理器接口只保留实际参与调度的能力。
- `DiamondConfigParser` 只做解析，不读数据库，也不更新缓存。配置写入前复用同一解析规则。`AlfredDiamondService` 在全部配置解析成功后一次发布快照；解析失败时保留上一份结果。快捷项写入提交后刷新缓存，分页在同一缓存引用上完成。
- 网页集合批量读取集合、分组和网页，再由无数据库依赖的 `WebCollectionResponses` 组装响应。LLM 会话列表批量计数，完整详情批量读取消息，避免按每个集合或会话重复访问数据库。写入流程保留原有事务、数字 ID、排序及逻辑删除规则。
- 计算器为每次请求创建独立的 `ArithmeticExpression`。除零等非有限结果统一显示计算错误。`YoudaoTranslationService` 封装语言选择、V3 签名、表单请求及响应解析，使用 JDK HTTP 客户端。翻译词典中的坏项不会丢弃其他合法结果。
- 外部 HTTP 调用保留线程中断信号。日结取消后不再发送回退通知。日结首行统一为 `TL;DR：`，摘要限制为 40 个 Unicode 码点，正文保持原样。
- JSON 保存由数据库唯一约束处理并发去重；冲突后重查已提交的记录。搜索字段优先级和预览来源共用同一份映射。请求中的数字 ID 必须能精确表示为 long；小数、溢出，以及排序列表中的空 ID，都会被拒绝。

回归测试覆盖 Alfred 调度及限流、配置解析失败时保留缓存、快捷项增删改后的可见性、批量查询的归属及顺序，以及 H2 排序失败后的事务回滚。修改这些流程时，在 Java 21 下运行 `mvn test`。无需启动游戏验证。

## 检查

```bash
mvn test
mvn package
```

浏览器回归检查位于 `src/test/browser/workspace-navigation.cjs`。它使用模拟数据验证页签状态、快捷项 CRUD、JSON 详情和历史导航。运行前设置 `PLAYWRIGHT_MODULE_PATH` 和 `CHROME_PATH`。
