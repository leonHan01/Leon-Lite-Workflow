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
| `/workspace/database.html` | 数据库管理：表行数、逻辑内容大小与只读 SQL 查询 |
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

在管理页侧栏打开「工作流编排」，新建流程自带且仅有一个「开始」和一个「结束」节点，默认直接连通，两者不能删除。可在中间添加 HTTP 请求、JavaScript、Groovy、IF、飞书消息、QQ 消息或发送邮件节点。双击节点可展开配置并聚焦编辑，配置面板已收起或处于全屏时也可使用；Enter / 空格同样可打开配置。拖动节点标题调整位置；用鼠标按住节点右侧出口，拖动预览线到目标节点左侧入口后松开完成连线；松开到空白处或按 Esc 取消，也可在节点配置的「连接出口」中添加后续节点。同一出口可以连接多个不同节点。IF 分别配置 true / false 出口。开始节点不能有入口，结束节点不能有出口；发布时所有路径（包括 IF 的 true / false）必须连接到同一个结束节点，单条路径的数据直接作为最终输出；多条执行路径的 items 按图中连线的广度优先顺序稳定汇总为最终输出。一期支持单起点、无环流程和条件分支，最多 65 个节点（含开始和结束，为原有 64 节点流程预留结束节点）；同一出口的多个后续节点并行执行，每个流程最多同时执行 4 个节点，各自接收上游输出的独立副本。某分支完成后立即推进自己的后续节点，无须等待同层其他分支；分支失败只阻止该路径的后续节点，其他独立分支继续。所有分支结束后，如有失败则实例失败，否则结束节点执行一次；取消、总超时或服务关闭会中断运行节点并阻止排队节点继续，通知失败不自动重试。仅结束节点支持分支汇入，不支持处理节点汇合、循环或 Webhook 触发；定时执行在工作流配置中启用。

工作流列表直接显示定时规则、启用/暂停状态、规则时区和下一次执行时间（北京时间）；未设置与已暂停分别标识。定时信息通过列表查询关联读取，不额外逐个查询定时配置；启用定时的列表每 15 秒刷新，窄屏在名称下显示时间信息。

没有未保存修改时「保存草稿」置灰，节点定义与已发布版本一致时「发布版本」置灰；保存了尚未发布的草稿后发布仍可用，首次发布仍可用，仅重命名工作流无需发布新版本。只查看配置不产生变更，修改后还原原值会重新比较实际内容。

左侧节点库按「数据获取」「AI 与分析」「脚本与控制」「消息通知」分类，支持各组独立展开/折叠与一键全部收起/展开；折叠只影响显示，不产生流程修改。卡片约 54px 高，保留图标、名称和说明。支持按住卡片拖到画布指定位置，预览框标出落点，松开后创建并选中节点，再通过端口连线。拖入时按当前缩放和滚动位置换算坐标，已有节点及连线保留；选中结束节点时也可以拖入新节点。松开到画布外、按 Esc 或取消指针操作不会创建节点。点击节点卡片仍按原规则添加后续节点，键盘 Enter / 空格同样可添加节点或展开/折叠分类。

连线可以点击选中，高亮后在右侧「连线配置」中修改起点、出口和终点，或删除连线。也可拖动蓝色端点至新节点的端口重新连接，或点击「重新连接起点 / 终点」后点击对应端口。Delete / Backspace 删除选中连线（在输入框中编辑时不触发）；Esc 取消重连并保留原线。重新连接保留连线 ID 和执行顺序，不覆盖其他连接。重复连线、循环、开始节点入口和结束节点出口会被拦截。

「保存草稿」允许保存尚未连接完整、配置尚未填写的流程，但必须保留且仅有一个开始和结束节点；「发布版本」先保存再校验连线、节点和配置，生成不可变版本。「运行 vN」始终执行该发布版本，未发布的编辑不参与运行。原有流程读取时将手动触发节点转换为开始节点，并为未连接出口补上结束节点；保留原有节点 ID、配置、发布版本及历史执行记录，保存草稿时持久化新结构。下方可以填写手动输入、调试当前草稿节点、查看最近 50 次运行的节点输入、输出、日志、错误和耗时，也可以停止运行。「载入双语言示例」提供一条可直接发布执行的示例：120 → JavaScript 加 5 → Groovy 翻倍 → IF 判断 → 250。

编排画布默认提供更大的可视高度和 2400 × 1600 的初始范围，节点靠近边缘时自动扩展，也可点击「扩展画布」继续增加范围。工具栏支持 5%–200% 缩放、「适应视图」、100% 重置、全屏编辑，以及收起节点库和配置面板；拖拽空白处平移，Ctrl / ⌘ + 滚轮以鼠标位置缩放。全屏模式自动收起两侧面板，点击节点可展开配置，Esc 或「退出全屏」返回。部署完成后，监控页提供「刷新界面，加载最新功能」入口，完整加载新菜单；切换页签只更新内容区域。

**飞书消息。** 从节点库添加「飞书消息」，使用所附飞书图标。点击「添加飞书机器人」，将飞书群自定义机器人的 Webhook 地址及可选签名密钥保存为加密凭据，再选择文本或 Markdown 卡片，填写消息内容及卡片标题。内容和标题支持 `{{ input.items[0].json.name }}` 等字段引用；字段只解析一次，上游文本中的类似标记保持原样。每次节点执行发送一条消息，成功后向下游传递原输入；失败时节点及流程失败，不自动重试。调试按钮「发送测试消息」也会实际发送。只接受 `https://open.feishu.cn/open-apis/bot/v2/hook/...` 的官方 HTTPS 地址，复用 HTTP 节点的地址校验、超时和禁止重定向策略；发送的完整 JSON 请求体最多 20 KB。Webhook 地址和签名密钥不写入流程定义，凭据接口只返回 ID、名称和类型，运行记录隐藏已知凭据回显。机器人启用签名校验时按[飞书自定义机器人文档](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot?lang=zh-CN)生成时间戳与签名；关键词、IP 白名单、频率限制等服务端错误会使节点失败。

**QQ 消息。** 从节点库添加「QQ 消息」，点击「添加 QQ 机器人」，将 QQ 开放平台机器人的 AppID、AppSecret 保存为加密凭据。可编辑已有凭据，AppSecret 不回显，留空保留原值；更新 AppID / AppSecret 后下一次执行会刷新缓存令牌。QQ 凭据不能用于 HTTP、飞书或 SMTP 节点。发送直连 `https://api.bot.qq.com`，凭据、令牌和平台原始错误回复不会保存到运行记录。

选择机器人凭据和「QQ 单聊」后，点击「绑定我的 QQ」，等待页面显示一次性绑定码；用要接收通知的 QQ 在与该机器人的私聊中发送完整绑定码，页面会自动将 `user_openid` 填入「接收目标 OpenID」。绑定群聊时选择「QQ 群聊」，点击「绑定 QQ 群」，在目标群中 @机器人并发送绑定码，自动填入 `group_openid`。先将机器人添加为好友或加入目标群，确保机器人具备对应消息事件权限。绑定完成后保存草稿并发布新版本，后续运行才会使用该目标。无需运行脚本或配置公网回调。

服务器通过 QQ 官方 `/gateway` 接口获取地址，再临时建立 WebSocket 连接；兼容 `api.bot.qq.com` 和 `api.sgroup.qq.com` 两个官方域名及 `/websocket`、`/websocket/` 两种路径，其他域名、端口、查询参数和非 WSS 地址仍拒绝。失败时显示具体阶段及脱敏后的平台错误码，不返回原始平台回复。绑定码有效 120 秒，仅匹配本次随机码及所选单聊 / 群聊范围；成功、取消、超时或服务停止即断开。离开编排页、切换工作流 / 节点、切换机器人凭据或目标类型时取消当前绑定，迟到结果不会覆盖其他节点；修改凭据后需重新绑定。同一机器人同时仅允许一个临时绑定，整个服务最多两个；若同一机器人已有其他网关连接，应避免同时绑定。绑定接口位于 `/api/workflows/qq-bindings`，沿用管理 Cookie 与写入来源校验，不开放公网事件接收接口。绑定码和原始 QQ 事件不保存到数据库、流程或运行记录，只有取得的 OpenID 在保存草稿时进入节点配置。

也支持手动配置：填写当前机器人的用户 `user_openid` 或 `group_openid`（不是 QQ 号或群号，同一用户在不同机器人下的 OpenID 不通用）。若已有官方消息事件，可展开「从 QQ 消息事件提取接收目标」，粘贴完整 `C2C_MESSAGE_CREATE` / `GROUP_AT_MESSAGE_CREATE` / `GROUP_MESSAGE_CREATE` 事件提取 OpenID；事件只在页面内解析，不上传或保存。没有事件接收服务时，可在交互终端运行一次临时绑定脚本：

```sh
python3 -m venv /tmp/leon-qq-target-venv
/tmp/leon-qq-target-venv/bin/python -m pip install 'websockets>=12,<18'
/tmp/leon-qq-target-venv/bin/python scripts/qq_target.py --app-id 你的AppID
# 获取群目标时，追加 --target-type group
```

脚本隐藏输入 AppSecret，临时连接 QQ 官方 WebSocket 网关，订阅单聊 / 群聊事件。提示连接成功后，在与机器人的单聊中发送绑定码；群模式需在目标群中 @机器人并发送绑定码。脚本只输出匹配绑定码的目标 OpenID，成功或等待 120 秒后断开，不保存凭据、令牌和消息正文，不向 QQ 发送消息。不要与已有使用同一机器人会话的网关同时运行，以免产生连接冲突；若机器人尚未开放消息事件权限，先在 QQ 开放平台完成对应配置。

消息支持文本、Markdown 及 `{{ input.items[0].json.summary }}` 等上游引用，「使用上游摘要」可自动填入摘要字段。模板只解析一次，目标 ID 也支持引用。项目将每条正文限制为 **2000 UTF-8 字节**，中文通常占 3 字节，超长明确失败，不自动拆分、截断或重发；长报告应由上游先生成摘要。每次节点执行发送一条消息，QQ 返回消息 ID 后传递原输入；无新增 items 时跳过。「发送测试消息」会真实发送。获取令牌和发送共用节点超时（100–30000 ms），缓存最多 64 组凭据的访问令牌并提前刷新；失败、取消或超时后先确认接收情况，再手动重跑。

机器人须已添加为好友或加入目标群，开放平台及 QQ 客户端须允许对应主动消息；Markdown 另需相应能力。权限、拒收、非好友、频率 / 配额及内容拒绝会使节点明确失败，接口接受不代表接收方已阅读。参考 [QQ 消息收发规则](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)、[发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)、[官方事件订阅](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/event-emit.html)。

**发送邮件。** 从节点库添加「发送邮件」，在配置中点击「添加 SMTP 邮箱」，填写服务器域名、端口、SMTP 用户名、邮箱密码或专用授权码及发件人邮箱。连接支持 SSL/TLS（通常 465）和必须完成升级的 STARTTLS（通常 587），校验服务器证书与主机名，不支持明文发送；仅连接公网 SMTP 地址，并固定本次校验的 IP。连接信息和授权码使用现有加密凭据存储，流程定义只保存凭据 ID，HTTP / 飞书节点不能混用邮箱凭据。

填写收件人、可选抄送 / 密送、主题及纯文本或 HTML 正文；多个邮箱用逗号分隔，每封邮件最多 100 个收件人。上述内容支持 `{{ input.items[0].json.name }}` 等上游引用，只解析一次；主题和邮箱不允许换行，正文最多 1 MB。每次节点执行提交一封邮件，SMTP 接收后继续传递原输入，密送地址不写入邮件头。「发送测试邮件」也会实际发送。连接、读写和整体提交有超时限制，失败不自动重试；SMTP 接收不等于最终投递成功，失败、取消或超时后可能已有部分收件人收到，确认后再手动重跑。服务端回复可能包含认证数据，因此错误按认证、DNS、连接、超时、TLS 与邮件拒收分类，仅提取 SMTP 数字响应码，不保存原始 SMTP 回复。

**GitHub Trending 分析。** 节点库中的「GitHub Trending」支持周榜 `https://github.com/trending?since=weekly` 和月榜 `https://github.com/trending?since=monthly`；在节点配置中选择榜单周期，服务器通过 HTTPS 抓取对应地址，用 Jsoup 解析 `article.Box-row` 中的仓库名称、链接、描述、语言及 Star 信息，然后将这些结构化数据交给所选 DeepSeek 或 OpenAI 模型逐个分析项目用途和适用场景。模型从高级设置的统一配置中下拉选择，节点仅保存 `modelId`；未选择统一配置的旧节点继续默认使用 `deepseek-flash` 和现有 DeepSeek Key。无需模型联网，页面脚本不执行；网页文字作为资料处理，不作为指令。密钥只从受保护的设置或加密凭据读取，不保存在节点或运行输出中，模型采用 JSON 输出模式。默认最多 25 个项目，可设置 1–25 个；不足上限时分析全部项目。默认总超时 180 秒，可设置 1–240 秒，抓取阶段最多 30 秒，沿用工作流 HTTP 的地址校验、1 MB 响应限制和不自动重试策略。榜单不可访问、解析为空或异常、分析项目遗漏 / 重复 / 顺序不符、JSON 无效或结果截断都会明确失败。

输出为一个 `items[0].json`，包含 `sourceUrl`、`period`（weekly / monthly）、`periodLabel` 和 `projects`（仓库资料、`purpose`、`useCases`）、`summary`、`count`、`totalProjects`、`generatedAt`，以及准备好的 `qqMarkdown`（加粗标题、项目名称和重点内容的单条 QQ Markdown 摘要）、`qqText`（兼容纯文本摘要）、`feishuText`（纯文本）、`feishuMarkdown`（带样式的 Markdown 卡片正文）、`emailSubject`、`emailBody`、`emailHtml`。飞书与邮件标题、正文均标注对应榜单周期和来源 URL；旧节点未选择周期或配置为日榜时使用周榜。节点库、画布与拖动预览使用统一的 GitHub 图标。飞书摘要按消息字节预算收短每项说明并保留所有项目及链接，邮件保留完整说明；HTML 正文统一转义。分析根据榜单描述，资料不足会明确说明，不声称已读取源码。调试时可在弹窗预览飞书正文和邮件正文，此节点只生成内容，不发送消息。

QQ 摘要加粗标题、项目名称、榜单概览和用途标签，模型文字中的 Markdown 符号转义后再按字节预算收短，保持格式闭合；兼容的 `qqText` 仍为纯文本。QQ 摘要包含榜单周期、日期、整体概览、按榜单顺序的前至多 5 个项目用途与完整仓库链接，以及榜单来源；总长度不超过 2000 UTF-8 字节，长仓库名或快照来源说明占用较多空间时会减少展示项目数，并提示其余完整解读见运行详情。快照数据会保留来源与采集时间，模型正文中的提及标签会转为普通文本。QQ 节点点击「使用 Trending 分析结果」会选择 Markdown 消息并填入 `{{ input.items[0].json.qqMarkdown }}`；调试分析节点可预览 QQ 摘要，不会自动发送。

将分析节点连接到 QQ、飞书、邮件节点（可以从同一出口分支到多个消息节点），在后续节点点击「使用 Trending 分析结果」即可填入正文引用：飞书自动选择 Markdown 卡片，标题使用 `{{ input.items[0].json.title }}`、正文使用 `{{ input.items[0].json.feishuMarkdown }}`，展示蓝色标题、彩色榜单/项目数量标签、加粗项目名与用途/场景标签、可点击的仓库链接；原有纯文本节点仍可使用 `{{ input.items[0].json.feishuText }}`，需要样式时重新点击此按钮后保存并发布。格式由后端统一生成并转义模型中的 Markdown 和提及语法，签名卡片按完整请求体字节预算收短说明，保留所有项目链接；邮件主题和正文分别使用 `{{ input.items[0].json.emailSubject }}`、`{{ input.items[0].json.emailBody }}`。如需 HTML 邮件，将格式改为 HTML，正文改用 `{{ input.items[0].json.emailHtml }}`。后续仍需配置飞书机器人、SMTP 邮箱和收件人，再发布运行或启用定时。

**RSS 订阅与通用 AI 总结。** 新增「RSS 订阅」支持 RSS 2.0、RSS 1.0 / RDF 与 Atom，最多 20 个来源，可按来源配置标题包含关键词。预置 InfoQ 中文、阮一峰（仅科技爱好者周刊）、少数派、IT之家、36氪、OpenAI News 和 GitHub Changelog。可设置最近多少小时、每源和总条数，按来源轮流选择，避免单一高频来源占满输出；文章正文为订阅源提供的正文或摘要，不额外访问原文。输出每条包含 `title`、`url`、`source`、`publishedAt`、`content`。一个来源失败时其余继续，错误出现在日志和输出 `rss.warnings`；全部失败则流程失败。地址检查沿用 HTTP 的公网 IP、超时、响应大小与禁止重定向策略，需填写最终 RSS 地址；XML 禁止 DOCTYPE 与外部实体。

默认仅处理新增文章，链接（忽略追踪参数及片段）和来源条目 ID 共同去重。H2 的 `automation_rss_seen` 按工作流 / 节点保存已处理键，`automation_rss_pending` 暂存本次输出键；只有整个流程成功时才与实例成功状态在同一事务中确认，失败、取消和重启清理本次暂存键，历史去重状态保留。调试预览不读取或写入历史去重状态。含 RSS 的同一工作流禁止重叠运行；其他流程的并发规则保留。外部发送与 H2 无法跨系统原子提交，若对端已收到但本次最终失败，手动重试仍可能重复发送。

「AI 总结」支持任意上游 `items` 数据，模型从高级设置的「模型配置」中选择 DeepSeek 或 OpenAI GPT；可配置标题、总结要求、条数上限、输入文本预算和超时。长输入均分预算截断并在输出标记 `inputTruncated`，不会静默丢弃超出条数上限的数据。空白或无效 JSON 响应在总超时内最多重试一次，不自动重试 HTTP 失败或发送操作；校验模型返回的条目与输入逐项对应，原文链接只来自输入；生成 HTML 时转义内容。输出 `summary`、`articles`、`emailSubject`、`emailBody`、`emailHtml`、`feishuText`、`feishuMarkdown`，邮件 / 飞书节点可用「使用 AI 总结结果」自动填入引用。飞书正文过长时按段落缩短，完整总结保留在运行详情和邮件字段。空输入不调用 AI，邮件和飞书节点也跳过发送。

`scripts/create_rss_workflow.py` 创建「RSS 科技资讯日报」：7 个来源 → AI 精选 10 篇 · 其余速览 → 已有飞书机器人，北京时间每天 09:00，默认最近 7 天、每源最多 5 篇、总计 35 篇。精选节点为 `ai-summary` 的 `mode: top10`，直接接收 RSS 原始文章，复用高级设置的 DeepSeek Key，一次调用评选阅读价值排名、核心看点、精选摘要与其余一句话速览；不足 10 篇全部精选。服务端校验全部文章 index、精选数量和排名范围；允许排名重复，并按排名、原输入顺序稳定排序，链接只来自原始输入，AI 不得替换。默认 `mode: summary` 保持普通汇总行为。

精选模式统一生成飞书 `feishuMarkdown` / `feishuText`、邮件 `emailSubject` / `emailBody` / `emailHtml`、QQ Markdown 字符串数组 `qqMessages`。标题、精选文章名称和核心看点加粗，HTML 与 Markdown 正文分别转义。完整 AI 分析保留在 `articles`；飞书正文按字节预算缩短说明并保留每篇原文链接。QQ 按完整文章分条，每条不超过 2000 UTF-8 字节，保留闭合格式与完整链接。QQ 节点点击「使用 AI 精选结果」会选择 Markdown、消息列表及 `{{ input.items[0].json.qqMessages }}`；发送前验证全部消息，依次发送，中途失败停止且不自动重试，错误与日志显示平台已确认的条数，手动重跑可能重复。邮件节点点击「使用 AI 总结结果」选择 HTML 正文；飞书使用 Markdown 卡片。

`scripts/update_rss_digest.py` 是显式迁移工具，须先部署支持精选模式的服务，再按需运行：将旧「AI 总结 → JavaScript 排版」替换为一个直接调用 AI 的精选节点，保留现有收件人、机器人、RSS 去重及定时配置；使用历史 RSS 输出预览并验证后再发布，不触发任何通知发送，也不消耗新增状态。新建流程脚本 `--send-initial` 会真实发送。旧的 `scripts/workflows/rss_digest_format.js` 仅保留供历史流程兼容。验收：`WorkflowAiSummaryTest`、`WorkflowQqTest`、`WorkflowRssTest`、`python3 -B src/test/python/rss_digest_migration_test.py` 及 `src/test/browser/workflows.cjs`。

**定时执行。** 在工作流右上方点击「定时执行」，选择每分钟、每 5 / 15 分钟、每小时、每天 09:00、工作日 09:00，或填写六段 Cron（秒、分、时、日、月、周）。默认时区为 `Asia/Shanghai`，可使用其他 IANA 时区，预览会显示该时区下接下来 5 次执行时间。先发布工作流才能启用，启用后每次执行当时最新的已发布版本；草稿编辑不参与定时运行。定时输入单独保存，首次配置默认复制当前手动运行输入；HTTP 和飞书消息节点继续通过凭据 ID 引用加密凭据。

定时配置保存在 H2，服务重启后保留。同一工作流已有排队或运行实例时，跳过本次定时执行点；暂停只停止后续触发，已有实例继续运行。服务停机错过的执行点不补跑，恢复后从下一个时间点开始。轮询使用独立线程，每秒检查一次；创建实例与推进下次执行时间在一个数据库事务内完成，防止并发轮询重复创建实例。队列已满时实例标记失败，不立即重试。`LEON_WORKFLOW_SCHEDULING_ENABLED=false` 可暂停整个工作流调度循环，默认启用；它不会启用原来独立开关控制的待办统计或日结任务。

**运行实例。** 从侧栏打开「运行实例」，按工作流、状态、手动 / 定时触发、实例 ID 或名称筛选，支持每页 20 / 50 / 100 条。页面展示版本、开始时间、耗时、计划执行时间和统计汇总，默认每 4 秒刷新；查看详情可以展开节点输入、输出、日志和错误，并停止排队或运行中的实例。已删除工作流的历史实例继续保留，列表接口不返回输入输出或脚本源码，详情沿用原有脱敏规则。

运行中的节点和失败节点在运行详情中自动展开，优先展示调试日志。日志实时记录节点输入 / 输出字段、items 数量、消息模板引用及解析字数；Trending 另记录抓取字节数、项目数量、DeepSeek 请求和完成阶段及耗时。日志不记录业务正文、收件人、凭据或提示词，沿用脱敏规则，最多 16 KB。飞书正文与卡片标题、邮件收件人 / 主题 / 正文引用的字段缺失或为空时明确报错，避免只发送固定前缀；IF 的 exists 等可选字段判断保持原有行为。节点失败后，运行详情展示「错误原因」和已有日志。选中或双击画布节点也可查看最近查看的运行所记录的失败原因；单节点调试失败直接显示在配置弹窗与调试区，重试成功后替换旧错误。JavaScript / Groovy 错误在能定位时包含源码位置，失败前日志沿用脱敏规则。HTTP 错误区分状态码、认证、DNS、连接、超时和 TLS；SMTP 错误保留分类、检查建议及数字响应码，不返回原始认证回复或凭据。新的错误细节由后续执行记录，历史运行只展示原有已保存的信息。

两种脚本共用 JSON 协议：输入、返回值都为 `{"items":[{"json":{...}}]}`。JavaScript 入口为 `function main(input, ctx)`，Groovy 为 `def main(Map input, Map ctx)`；`ctx` 仅包含 `runId`、`nodeId` 和节点参数 `params`。切换语言分别保留源码。JavaScript 使用 GraalJS 25.0.2，Groovy 使用项目 Maven 管理的版本。脚本不是 Node.js 环境，不提供 npm、文件或网络能力；HTTP 调用使用专门的节点。HTTP 返回 `items[0].json` 包含 `status`、`headers` 和 `body`。配置字段支持 `{{ input.items[0].json.name }}`、`{{ ctx.params.name }}` 形式的字段引用，不执行任意表达式。

脚本通过独立 Java 21 子进程运行，只传递 JSON 数据，并清空主应用环境变量。macOS 使用系统 `sandbox-exec`，Linux 必须安装 `/usr/bin/bwrap`（bubblewrap）和 `/usr/bin/setpriv`（util-linux），且允许非特权用户命名空间。脚本启动链先清除继承的进程能力，兼容主服务绑定低端口的权限；发行版 JDK 链接到目录外的时区及安全策略资源按文件只读挂载。沙箱只提供只读语言运行时、JDK 和必要系统库，禁止宿主业务文件读取及网络访问；JavaScript 额外关闭 Java 主机访问。隔离环境缺失或不可用时拒绝执行脚本，不回退到主进程执行。脚本 JVM 堆上限 128 MB，元空间 96 MB，直接内存 16 MB；单节点超时 100–30000 ms（默认 10 秒，包含编译启动），工作流最长约 5 分钟，最多同时执行 2 个任务、排队 8 个。超时和取消会终止子进程及其后代。操作系统沙箱隔离访问权限，JVM 内存限制不等于整个进程或进程树的硬内存限额；部署到共享或不可信多租户环境前还应通过 cgroup / systemd 施加进程树资源配额。

HTTP 节点仅接受 HTTP/HTTPS；默认阻止回环、内网、链路本地、云元数据及其他保留地址。连接时使用校验过的 DNS 地址，防止二次解析绕过限制；不自动重试、不跟随重定向。确需调用内部系统时，由运维通过 `LEON_WORKFLOW_HTTP_ALLOWED_ORIGINS` 明确配置允许的来源，例如 `http://127.0.0.1:9000`；多个来源用逗号分隔，准确匹配协议、主机和端口，不在节点中开放绕过选项。输入、输出和 HTTP 响应最大 1 MB、最多 1000 个 item；日志最多 16 KB。

「管理凭据」支持 Bearer、Basic、自定义 Header、飞书机器人 Webhook 和 SMTP 邮箱。启用前在应用私有环境配置中设置 `LEON_WORKFLOW_CREDENTIAL_KEY`，值为独立生成的 32 字节随机密钥的 Base64 编码。不要把密钥写进仓库、日志或网页存储，升级保留该配置；更换密钥前需安排凭据重新加密，不能直接替换。密钥不设置时仍可使用无凭据 HTTP 节点，凭据保存及读取会拒绝。凭据在 H2 中使用 AES-256-GCM 加密，列表和保存接口只返回名称、类型和 ID；编辑详情仅额外返回 SMTP 连接信息、用户名或 Header 名称等非密钥字段，发送凭据必须使用 HTTPS。运行记录会隐藏常见敏感字段及已知凭据的回显，脚本和配置中应只引用凭据 ID。SQL 备份包含加密后的凭据记录，恢复后还需原有服务器加密密钥。

已保存凭据可在「管理凭据」列表中点击「编辑」，也可从节点配置点击「编辑所选凭据」。保存沿用原 ID 和类型，所有引用此凭据的节点后续执行时使用新配置，无需重新发布工作流；不替换已经建立的连接。密码、令牌、Webhook 和签名密钥不回显，编辑时留空保留旧值，飞书签名密钥可显式清除。关闭弹窗会清空表单中的密钥输入。`GET /api/workflows/credentials/{id}` 返回可编辑详情，`PUT` 更新同一记录；均受管理页会话保护，更新还校验写入来源。

正式 ECS 的凭据主密钥保存在 `/etc/leon-lite-workflow-workflows.env`（root 所有、`0600`），由 `96-workflows.conf` 加载。`scripts/install_workflow_runtime.py` 会保留已有密钥、检查或安装 bubblewrap，并备份后补充受登录保护的 HTTPS 路由；它不单独重启应用。随后统一运行 `python3 scripts/deploy.py --description ...`，成功标记和备份清理前，会在线执行使用加密凭据的 HTTP → JavaScript → Groovy 流程，还会验证真实定时触发和独立实例列表，同时验证原有数据 ID 保留。验收只清理本次创建的测试流程、凭据与临时会话。

新增接口统一位于 `/api/workflows/**`，正式环境只允许管理页 Cookie 会话访问；写入还必须通过现有的准确 Origin 和 `X-Requested-With` 校验。插件/Alfred 的 Bearer 令牌不能直接管理或运行工作流，原有接口认证规则不变。页面和 `/workspace/` 资源沿用 HTTPS 代理的登录保护。

数据保存在原有 H2 内新增的 `automation_workflow`、`automation_version`、`automation_run`、`automation_node_run`、`automation_credential`、`automation_schedule` 表，不替换现有数据。节点开始和结束分别持久化状态；正常关闭服务会取消任务，异常重启会将遗留未完成任务标为失败，均不自动重放。历史实例新增触发方式和计划时间字段，旧实例默认标记为手动触发，已有 ID、版本、输入输出保留。HTTP 已发出、结果尚未保存时不能断言对端是否成功，取消也不能撤销已发送的请求；请确认对端结果或使用业务幂等键后再手动重跑。删除工作流使用逻辑删除并保留历史，执行中的流程须先停止。

工作流后端测试包含 `WorkflowGithubTrendingTest`、`WorkflowCredentialsTest`、`WorkflowIntegrationTest`、`WorkflowFeishuTest`、`WorkflowEmailTest`、`ScriptProcessRunnerTest`；浏览器测试为 `src/test/browser/workflows.cjs`，沿用 `PLAYWRIGHT_MODULE_PATH` 和 `CHROME_PATH`，验证飞书图标及机器人凭据、文本与卡片配置、SMTP 邮箱凭据及邮件字段、双击配置、新增节点、双语言源码保留、拖动、发布执行、分支状态、凭据及手机布局，不启动游戏。

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

科技爱好者周刊分析节点每次通过 GitHub 官方 API 读取 `ruanyf/weekly` 的仓库索引，选择最高期数并读取对应 `docs/issue-N.md`，使用所选 DeepSeek 或 OpenAI GPT 模型分析正文。节点提供 `title`、`sourceUrl`、`issue`、`summary`、`highlights`、`feishuMarkdown`、`feishuText`、`emailSubject`、`emailBody` 和 `emailHtml`。连接飞书和邮件节点后，点击「使用周刊分析结果」填入相应引用；该节点本身不发送消息。GitHub API 可能限制匿名请求频率，失败时会显示原因。

GitHub Trending 页面无法抓取时，节点会尝试使用 GitStock 的公开榜单快照；遇到暂时性的只读连接错误时最多请求三次，并记录每次请求的结果（支持周、月榜）。快照超过 48 小时或周期、项目、采集时间无效时会报错，不会继续分析；使用快照时，日志和飞书、邮件正文会标注来源及采集时间。GitStock 是第三方服务，快照可能与实时榜单不同。

工作台的「Leon Tools Pro」复用插件页面，并通过当前工作台登录会话访问同一个后端的集合、工具箱、便签、待办及 LLM 会话数据。页面通过服务器调用 DeepSeek；回复完整返回后显示。新增便签、添加或完成待办时，沿用插件的飞书通知行为，使用服务端已有机器人设置；未配置时跳过。网页没有 Chrome 扩展的标签页权限，因此当前页面、标签管理和扩展图标缓存不可直接复用；收藏图标使用网站 favicon。工作台不读取插件的浏览器本地缓存，尚未同步到后端的数据需先在插件中同步。聊天和通知接口仅允许工作台会话及受信任来源写入，不将访问令牌保存到网页存储。


「高级设置 → 模型配置」集中管理模型显示名称、服务、模型名称、OpenAI 凭据和启用状态，工作流页与 AI 节点均提供快捷入口。新增「AI 总结」（含精选模式）、「科技爱好者周刊分析」和「GitHub Trending 分析」节点仅保存 `modelId`；运行时读取当前配置。可编辑或停用模型，修改对所有引用它的节点生效，停用或缺失时明确报错，不自动切换模型。模型元数据持久化在 H2 `automation_model`，默认添加 DeepSeek Flash，升级不会覆盖已编辑配置。DeepSeek 模型同样使用下拉选择，提供 `deepseek-flash`（V4.1 Flash）和 `deepseek-v4-pro`（V4 Pro），默认保留 Flash，沿用现有 API Key；OpenAI 可在同页添加加密凭据，多个模型可复用同一凭据。已有节点的 `provider/model/credentialId` 仍兼容，界面显示“现有配置”，手动选取后才迁移为 `modelId`。密钥使用现有 AES-GCM 主密钥加密保存，列表和编辑接口均不返回原值，编辑留空保留原值；该凭据不能用于普通 HTTP、QQ、飞书或 SMTP 节点。

模型列表同时展示累计输入、输出和总 Token，以及已统计次数和未返回完整用量的响应次数。用量取自官方响应的 `usage.prompt_tokens`、`completion_tokens` 和 `total_tokens`，按请求所用的服务及模型名称合并，AI 工作流节点运行、节点测试、旧节点和 Agent 调用均计入；相同模型的多个配置共享统计，切换配置中的模型名称后显示对应模型的累计值。`automation_model_usage` 持久化统计，改名、停用和服务重启不会清零。自动 JSON 重试的每次响应和最终内容校验失败的响应都在校验前入账；缓存输入及推理输出已包含在对应总量中，不重复累加。仅统计功能启用后本系统实际收到的用量，不估算历史、网络中断或平台其他客户端的消耗；可点击“刷新用量”更新页面。


OpenAI 默认模型为 `gpt-6.1-sol`，在统一模型配置的「模型名称」下拉框中选择 GPT-6.1 Sol、GPT-6 Luna、GPT-6.1 Terra 或 GPT-6 Astra；模型选项随服务切换，已有配置中的其他模型会保留为可选项，避免编辑时被替换。使用固定的官方 `https://api.openai.com/v1/chat/completions`，OpenAI 请求发送 `max_completion_tokens`、JSON 响应格式与 `store:false`，不混入 DeepSeek 的 `thinking` 或 `max_tokens`。来源抓取、输入长度上限、原文链接校验与现有通知字段保持兼容；认证、模型权限或限流失败时显示服务名称和状态，不回显上游错误正文，不自动切换其他服务。参考：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[GPT-6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol)。验收：`WorkflowOpenAiTest`、`WorkflowCredentialsTest` 与工作流浏览器回归。


## Leon 消息客户端与 Agent 工作台

新增管理页面 `/workspace/agents.html`，从工作空间侧栏的「Agent 工作台」进入。配置显示名称、唯一 `@handle`、OpenAI / DeepSeek、模型 ID、系统提示词、独立 API Key 以及启用状态。配置 API `/api/agent-admin` 只接受管理页面 Cookie，会话写入沿用准确 Origin 和 `X-Requested-With` 校验；客户端 Bearer 不能配置 Agent 或读取提示词、Key。API Key 加密保存在 `chat_agent` 表，复用已有 `LEON_WORKFLOW_CREDENTIAL_KEY`，不得重新生成或轮换原密钥。编辑时 Key 不回显，留空保留；切换 provider 必须填写新 Key。

客户端目录为 `/Users/leon/Documents/ChatGPT/LeonApp`。macOS / Android 支持 Agent 私聊、创建群聊、选择 @Agent 和回复失败重试；页面另提供「聊天测试」。现有单所有者认证不变，私聊和群聊同步到同一令牌下的所有设备，没有新增多用户账号系统。

`GET /api/agents` 返回公开摘要；`GET /api/conversations` 返回会话目录；`POST /api/conversations/direct/{agentId}` 创建或打开 Agent 私聊；`POST /api/conversations/groups` 接收 `{name,agentIds}`。原 `/api/device-messages` 增加 `conversationId`（默认 `shared`）和 `mentionAgentIds`；群聊也识别正文中的独立 `@handle`，只有被 @ 的群成员回复。Agent 回复不递归触发其他 Agent。分页按会话隔离，另返回 `jobs`，显示等待、运行和失败；`POST /api/device-messages/{messageId}/agents/{agentId}/retry` 仅重试失败回复。老消息迁移至 `shared` 并保留 ID、正文及原幂等键。

模型使用当前会话至本次提问的最近 30 条 / 32,000 字符历史及角色提示词，请求固定官方 HTTPS Chat Completions 接口，最多输出 2048 tokens / 8000 字符，不执行工具，不跟随重定向。最多 4 个会话并行、256 个未完成任务，同会话按顺序执行。未开始的任务重启后继续，正在执行的任务重启后转失败，避免自动重复付费调用。超时后的手动重试可能产生新的模型费用。回复完成后一次性展示。

Agent 表与任务表随服务启动自动创建，不需要清空数据库。部署沿用 `scripts/deploy.py`；现有代理需支持 `/api/agents`、`/api/conversations`、`/api/device-messages`、`/api/agent-admin` 并保留认证头与 Cookie；`/workspace/agents.*` 沿用 workspace 的登录保护。本次开发未部署公网。

验证：`mvn -Dtest=AgentChatIntegrationTest,OfficialAgentModelClientTest,DeviceMessageIntegrationTest,DeviceMessagePersistenceTest,ApiAuthenticationIntegrationTest test`（Java 21）。模型适配器使用模拟 HTTP 响应验证，未使用真实 API Key 联调。


### Agent 头像配置与上传

Agent 工作台增加头像选择和上传。内置用户提供的 30 张原始 PNG，位于 `src/main/resources/agent-avatars/`，保留透明度与原始比例。`chat_agent.avatar_id` 启动时自动新增，旧 Agent 默认 `alfred`，不修改已有密钥、模型配置和消息。

`GET /api/agent-avatars` 返回头像元数据；`GET /api/agent-avatars/{id}` 沿用 Cookie / Bearer 认证返回 PNG，并使用私有缓存。`POST /api/agent-admin/avatars` 仅接受管理 Cookie 和准确 Origin / `X-Requested-With`，multipart 字段为 `file`。真实格式只允许 PNG / JPEG，最多 5 MB，单边不超过 4096 像素；解码后等比居中生成 512×512 PNG，保留透明背景。不会信任文件扩展名或拼接原始文件路径。

自定义头像以内容 SHA-256 标识去重，PNG 字节持久化在 H2 的 `chat_agent_avatar` 表中，随既有数据库备份；不依赖临时上传目录，无需新增服务端目录或改变部署端口。最多保存 1000 张自定义头像。Agent 管理请求可附带 `avatarId`，只接受预设或已上传的 ID，不接受外部 URL；省略时旧 Agent 保留选择。公开摘要附带 `avatarId`，Mac / Android 使用自身 Bearer 请求加载图片，不把令牌加入 URL。

验证：`mvn -Dtest=AgentAvatarIntegrationTest,AgentChatIntegrationTest,OfficialAgentModelClientTest,DeviceMessageIntegrationTest,DeviceMessagePersistenceTest,ApiAuthenticationIntegrationTest test`（Java 21）。28 项测试通过；本次未部署公网服务。


### 输入 @自动候选

网页聊天在群聊输入框的光标处检测 `@` / `＠`，展示当前群内已启用的 Agent，按中文名称或标识筛选，带头像，最多显示 8 个匹配项。可点击、上下方向键选择、Enter / Tab 确认、Esc 关闭；Shift+Enter 仍换行。输入法组词期间不拦截确认键，邮箱中的 @ 和选中的文本不触发。私聊不展示其他 Agent 候选，切换会话会清理候选。

补全替换当前光标所在的整个 @片段，保留后面的正文，补齐分隔空白并移动光标，不会自动发送消息或触发模型。前端共享算法位于 `/workspace/mentions.js`，检查使用 `node --test src/test/js/agent-mentions.test.cjs`。Mac / Android 客户端同步更新至 0.4.0。

本次只改动客户端与网页交互，未部署公网服务。

### Agent 统一模型、工作空间与 App 通知

Agent 工作台使用共享侧栏和独立机器人图标；页面按工作空间生命周期激活，隐藏时暂停聊天与设备状态轮询。新 Agent 从已保存模型中选择，`chat_agent.model_id` 保存引用；每次回复读取最新模型与凭据，停用模型会阻止调用，不复制共享 API Key。旧 Agent 保留独立加密凭据，切换到共享模型后不再使用旧 Key。

工作流「消息通知」中增加 `app-message`（Agent 发送 App 消息）：配置 `agentId`、`targetType`（`direct` / `group`）、可选 `conversationId` 和 `text`。正文支持上游模板，最多 8000 UTF-16 单元；空 items 跳过发送，原输入继续传给下游。默认写入此 Agent 的私聊，群聊要求 Agent 为群成员。消息使用 Agent 的名称与头像，不调用模型，不触发其他 Agent；同一个运行实例和节点标识保证本地重入不重复。单节点测试会发送真实消息，重新运行工作流会发送新消息。

0.5.0 客户端在线时约每 10 秒通过 `POST /api/device-messages/presence` 上报设备标识、名称、平台。控制台通过 Cookie 保护的 `GET /api/agent-admin/devices` 每 5 秒刷新数量及列表；45 秒没有心跳视为离线，状态不持久化，服务器重启后由客户端再次上报。普通网页聊天不计入设备，旧客户端需更新才能参与统计。离线消息保存在原会话，下次打开 App 同步；尚未提供系统级离线推送。

HTTPS 代理必须转发消息相关路径并保留应用鉴权。首次启用运行 `python3 scripts/install_messaging_proxy.py`：备份配置、校验后平滑重载，仅增加限定消息路由，保留默认拒绝与服务端 Bearer / Cookie 校验；Agent 管理仍要求管理会话。普通消息及图片路径上传上限为 6 MB；聊天备份的准确路径 `/api/conversations/backups` 单独允许 32 MB。客户端分别提示认证失败（401）、服务器/代理拒绝（403）和服务不可用（502/503/504）。

验证：`mvn -Dtest=AgentWorkflowIntegrationTest,DevicePresenceTest,AgentChatIntegrationTest,AgentAvatarIntegrationTest,WorkflowIntegrationTest,WorkflowParallelExecutionTest test`；`python3 scripts/test_messaging_proxy.py`。

### 网页选择 Agent 向 App 发消息

在「Agent 工作台」右上角点击「向 App 发消息」，打开聊天窗口。选择已启用的 Agent，默认发送至与该 Agent 的私聊，也可以选择包含它的群聊。正文按填写内容直接以该 Agent 身份保存，使用其名称和头像，不调用模型、不生成回复任务。已在配置表单选中的 Agent 会默认带入窗口。

窗口显示目标会话最近 100 条消息，并自动同步 App 的回复。正文上限 8000 UTF-16 单元；发送失败保留正文和消息标识，原样重试不会重复保存。消息保存成功代表服务器已接收，在线 App 轮询后显示，离线设备下次打开同步，不代表设备已读。

管理接口 `POST /api/agent-admin/messages` 接收 `{agentId,conversationId?,text,clientMessageId}`，`clientMessageId` 为 UUID。省略会话时打开该 Agent 的私聊；群聊要求 Agent 为成员。接口同时要求管理页 Cookie 及有效写入来源，Bearer 令牌不能代替管理登录；同一消息标识改写内容或会话会返回 409。已有 App 使用原收消息协议即可显示。


### Helper 主动关怀

Agent 工作台可点击「让 Helper 发一条消息」，为当前账号立即生成一条主动私聊，不占每日自动发送次数，也不受自动发送时段限制。需要先与 Helper 进行过有效对话；后台显示发送结果，网络中断后可再次点击查询同一任务。

已启用的 `@helper` Agent 每天北京时间 09:00–22:00 为每位有过实际对话的用户随机安排两次主动私聊，两个时间点至少间隔一小时。优先按标识 `helper` 识别；没有该标识时，使用唯一名为 `Helper` 的 Agent。`LEON_AGENT_HELPER_ID` 可固定指定已有 Agent ID；不会创建或覆盖 Agent 配置。用户需要曾向 Helper 发言且得到回复，私聊或群内定向回复均可；仅打开会话不满足条件。

Helper 可以读取该用户有权访问的所有 Agent 私聊及群聊，包括群内其他成员的发言。已有压缩摘要优先复用，新增文字分批生成持久化会话摘要，再汇总为用户当前情况；每次模型输入均检查预算。不会把全量聊天直接塞进一次请求，也不读取图片原始内容。群内各发言者分别记录，避免把他人的经历归给当前用户。消息以简短问候、关怀或一个跟进问题为主，发到用户最近使用的 Helper 私聊；只有群聊记录时创建该用户与 Helper 的私聊。模型和用量统计沿用 Helper 的配置，用量归接收用户。

计划和发送状态保存在 H2，重启不重新抽签、不重放中断的调用。错过超过 5 分钟的触发不补发；生成失败、停用、已无对话记录或超过 22:00 时不发送，因此实际每天最多两条。清空会话时删除 Helper 缓存，重置话题后旧缓存失效；生成期间清空、重置或退出来源群聊会丢弃本次结果。消息进入已有 App 同步通道，在线设备轮询收到，离线设备下次打开同步。

`LEON_AGENT_HELPER_ENABLED=false` 可关闭本调度；与工作流调度、待办日结独立。测试设置 `leon.agents.dispatch-enabled=false` 时默认同时关闭 Helper 后台调度，也可单独覆盖 `leon.agents.helper-enabled`。本地开发不自动部署或重启正式服务，需部署后生效。

验证：`mvn -Dtest=HelperOutreachIntegrationTest test`。

### Agent 对话上下文与 AI 压缩

每次回复由服务端组装 Agent 指令、历史摘要、近期完整轮次和当前问题。同一会话按问题顺序执行；私聊、群聊以及各 Agent 的摘要分别隔离。其他 Agent 的发言保留身份，作为参考资料传入，不冒充当前 Agent 的回答。主动发送到 App 的消息也会进入后续上下文。错误提示不作为聊天内容传给模型。

默认保留最近 10 轮（可设 1–20）；为减少额外调用，较早历史积累 4 轮或接近预算时才压缩，因此压缩前最多暂存额外 3 轮。按完整轮次裁剪近期窗口，较早部分用当前 Agent 所选模型合并为摘要，保留目标、偏好、决定、事实与未完成事项。摘要输入分批限制长度，输出按约 2,000 Token 的估算上限校验，不静默截断消息。常用 OpenAI / DeepSeek 模型的上下文输入预算约 12,000 Token，其他模型保守使用 5,500；另为模型回复预留 2,048 Token。这是长度估算，实际计费以模型返回的 usage 为准，压缩调用同样累计用量。

`chat_context_summary` 保存带历史边界的摘要版本，`chat_context_snapshot` 保存每个回复任务的请求快照，`chat_context_policy` 保存保留轮数和新话题边界；均持久化在现有 H2 中，不删除原消息。失败回复重试复用已有快照；压缩中断时保留已完成批次，手动重试从该边界继续，不能带入原问题之后的消息或摘要。服务重启不自动重放收费调用。新话题不再使用旧摘要，原话题的失败任务不能在新话题下重试。

`GET /api/conversations/context?conversationId=...` 返回该会话的摘要、近期请求估算 Token、原文轮数与压缩进度；`POST /api/conversations/context/settings` 和 `/reset` 接收 `{conversationId,recentRounds}`。沿用设备 Bearer / 管理 Cookie 认证及浏览器写入来源校验。会话有排队或执行中的回复时拒绝修改设置。Mac App 右上角「上下文」提供查看、设置与新话题入口；聊天任务的 `phase=COMPRESSING` 显示「上下文压缩中…」，随后切换至「正在回复…」。压缩失败明确提示并保留原记录。OpenAI 的压缩与回复继续经 Mac 执行器，DeepSeek 继续在服务器执行。

### 主动压缩、结束本轮与 LeonBook 归档

Mac 聊天输入 `/` 呼出命令菜单，支持方向键、Enter/Tab 和 Esc。`/compact`（别名 `/compress`）立即将较早对话用当前 Agent 模型压缩为摘要，保留最近一轮完整原文，无需等待自动阈值；`/context` 查看上下文；`/new` 结束本轮，可选择仅开启全新上下文或先导出再开启；`/export` 导出当前轮次并保留当前上下文。群聊明确选择总结或压缩使用的 Agent，命令不会写成普通消息或触发普通聊天回复。

主动任务持久化在 `chat_context_operation`，按会话与回复互斥，重复请求标识不重复收费。原始对话不删除；重启中断的运行任务标为失败，不自动重放模型调用。`POST /api/conversations/context/compact` 和 `/archive` 接收 `{conversationId,agentId,requestId}`，立即返回 202。分页消息的 `contextOperations` 显示进度和结果。归档冻结当前话题的完整原文边界，使用 AI 对全量历史分批总结，结果由 `GET /api/conversations/context/archive/{id}` 读取，不改变实时上下文。

Mac 经已安装的 `leonbook` CLI 通信：先固定 `workspace.info` 返回的 root/user，调用 `media.upload` 复制图片和完整原文附件，再 `articles.create` 保存草稿并 `articles.get` 读回校验。文章先展示 AI 总结，再展示原始对话；内容过长时原文完整保存在 Markdown 附件中。文章 slug 和归档标识固定，重复导出不覆盖用户改动或重复创建文章；不直接写 LeonBook 内部数据库或文章文件。

仅在本机确认文章保存后调用 `POST /api/conversations/context/archive/{id}/finish` 开启全新上下文。服务器比较冻结边界与当前话题，若期间收到新消息或已切换话题，则保留文章并拒绝重置，防止丢失未归档上下文。归档失败不重置，历史聊天保留。CLI 集成验收只使用临时资料库，不创建真实测试文章。归档与普通对话、主动压缩均沿用已有认证、OpenAI Mac 执行路径及模型用量统计。

### OpenAI 执行端安装

工作流内置 OpenAI 节点和 Agent 可切换到 Mac 执行器；DeepSeek 仍由服务器直接调用。Mac 主动通过 HTTPS 领取任务，无需开放本机端口。后台执行器随用户登录自动启动，运行时使用 `caffeinate -i` 防止自动闲置睡眠；手动睡眠、退出登录、关机或断网都会使 OpenAI 执行器离线。高级设置显示执行位置与在线状态。

服务器部署当前版本后，使用经过 SHA-256 校验的 `scripts/install_ai_executor_proxy.py` 配置限定的反向代理路径。Mac 安装命令：

```bash
python3 scripts/mac_ai_executor.py install --server https://121.40.254.112
```

安装使用本机已有 `~/.config/leon-workflow/api-token` 临时登录并注册独立执行令牌，保存为 `~/.config/leon-workflow/ai-executor/config.json`（0600），结束时退出管理会话。执行令牌仅能领取、确认和回传 OpenAI 任务，不能管理工作流、Agent 或读取其他凭据。OpenAI API Key 通过已认证 HTTPS 任务在内存中使用，不写入 Mac 配置或日志。服务端任务请求与结果使用既有主密钥 AES-GCM 加密，代理禁止请求和响应落盘缓冲；日志不记录提示词、正文、密钥或令牌。普通 SQL 备份排除执行器令牌、运行租约和执行模式，完整部署 H2 快照仍保留数据库原状。

只有 Mac 完成网络自检并回报在线后，安装才启用持久化的 Mac 执行模式。离线时明确失败，不回退到服务器或其他服务商。未开始的过期领取租约可以重新领取；确认开始后的请求不会自动重放。取消、重启、超时或回传丢失后需要确认再手动重试。结果回传可幂等重试，Token 用量仅入账一次，包括任务超时后迟到的有效响应。Mac 不执行脚本或任意 URL，网络目标固定为 OpenAI 官方 Chat Completions API。

停止执行器（不会把 OpenAI 切回服务器）：

```bash
python3 scripts/mac_ai_executor.py stop
```

### 清空当前会话（/clear）

Mac 输入 `/clear` 后先读取会话状态，展示消息数量和永久删除确认。确认后删除当前会话全部已保存消息、图片、摘要、上下文快照、服务端归档缓存与任务记录，并清理当前设备该会话的草稿、未发送内容和缓存；保留会话、Agent 配置、模型用量、其他会话及已导出的 LeonBook 文章。运行中的回复、压缩或总结会阻止清空；确认期间收到新消息或发生其他清空时要求重新确认。旧消息和图片标识仅保留无正文的去重标记，防止离线重试重新生成被删记录。此为逻辑删除，不清除既有运维备份。

`GET /api/conversations/context/clear-preview?conversationId=...` 返回 `messageCount/latestMessageId/historyRevision/hasActiveWork`。`POST /api/conversations/context/clear` 接收 `{conversationId,requestId,expectedLatestMessageId,expectedHistoryRevision,confirm:true}`，在事务内清空并增加历史修订号，同一 requestId 的重复请求不会再次删除新消息。消息分页返回 `historyRevision`，新版 Mac 和网页在修订变化时丢弃旧历史缓存；旧版客户端重新打开会话后刷新。沿用现有 Bearer / Cookie 认证和浏览器写入来源校验。

客户端显示“服务端尚未支持 /clear”时，需要先部署本次服务端代码。服务端默认由用户自行部署，功能开发不会自动部署或重启线上服务。

### Android 快捷命令、独立对话与聊天备份（0.10.0）

聊天输入区「＋」提供相册、压缩上下文、查看上下文、对话列表、备份记录、加载备份及清理记录。压缩使用当前 Agent（群聊先选择），显示后台进度；上下文页可查看摘要和最近请求的估算用量，并修改保留轮数。清理前读取消息数量及历史修订并要求明确确认；有进行中的任务或确认后收到新消息时拒绝清理。

点击 Agent 列表或聊天气泡中的头像可查看该 Agent 的独立私聊列表，切换已有对话或新建命名对话。原有固定私聊及 ID 保留。`POST /api/conversations/direct/{agentId}/threads` 接收 `{requestId,name}`，按请求 UUID 幂等创建 `thread:<UUID>`；每个对话独立保存消息、上下文和调度队列，不触发模型调用。

`GET /api/conversations/backups?conversationId=...` 返回 ZIP，包含版本化 `manifest.json`、当前会话的全部原始消息与时间、回复关联、原始 JPEG 图片、当前话题边界、保留轮数和各 Agent 最新压缩摘要；不导出模型配置、凭据、任务或请求快照。最多 10,000 条消息、32 MB ZIP、16 MB manifest。超过限制明确报错，不静默省略历史或图片。Android 使用系统文件选择器保存和读取，备份文件包含聊天内容，需妥善保存。

`POST /api/conversations/backups?requestId=<UUID>&agentId=<可选目标Agent>` 接收 `application/zip`。确认后事务性恢复为 `restore:<UUID>` 独立会话，保留原会话；为消息和图片生成新 ID，映射回复、图片与上下文边界，不重放模型任务。失败时不保留部分导入，相同请求及文件重试返回原导入结果，不重复插入。私聊可指定当前 Agent；群聊保留成员，需要对应 Agent 仍存在。格式版本、路径、重复条目、展开体积、图片尺寸及消息关联均校验，不解压到文件系统。

接口沿用 Bearer / 管理 Cookie 及浏览器写入来源校验。上线需部署本次服务端代码，并使用更新后的 `scripts/install_messaging_proxy.py` 安装备份路径的 32 MB 限制；本次开发不自动部署或重启正式服务。Android 收到历史修订变化后丢弃旧缓存与待发记录，避免其他设备清理后重新显示旧消息。

数据库管理页面显示各表字段内容的逻辑字节数，不包含 H2 索引、压缩和存储开销；统计超时或类型不支持时显示「—」。SQL 查询仅接受单条 SELECT/WITH，在临时只读账户下执行，最长 5 秒，结果最多 200 行；需要管理会话，查询请求还校验写入来源。页面与接口沿用受保护的 `/workspace/` 和数据库备份代理路由，不记录查询语句或结果。
