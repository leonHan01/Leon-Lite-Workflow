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
| `/json-record-search.html`、`/json-record.html` | JSON 记录与详情 |
| `/monitor.html` | 服务器监控 |
| `/music.html` | 声屿歌曲管理，支持多首上传、搜索、试听和删除 |
| `/advanced-settings.html` | 高级设置，含数据库 SQL 备份 |
| `/admin-login.html` | 访问令牌登录 |

**备份。** 高级设置下载的 SQL 包含 H2 表结构和业务数据，可导入空的 H2 数据库。其中不含数据库用户密码、浏览器登录会话、环境变量和服务器配置。备份可能包含私人内容，以及应用数据中的密钥，请单独保管。

**监控。** 侧栏打开 `/monitor.html`。每分钟采集系统 CPU、物理内存（总量减空闲量）和根文件系统磁盘使用率，内存中最多保留 24 小时，服务重启后重新积累；指标不可用时显示「—」。PV 统计工作空间页面的首次打开、刷新和站内切换，按北京时间把每日汇总写入 H2，从启用之后开始记录，不包括 API、Alfred 和插件请求。图表使用随服务提供的 ECharts。监控接口沿用现有认证，以及管理写入的来源限制。

**部署记录。** `/monitor.html` 同时展示最近 50 次部署的北京时间、版本、Git 提交、工作区是否包含未提交改动、说明、结果和耗时，并标记当前实际运行版本。成功、失败、部署中及回滚均有独立状态，历史文件持久化保存，普通重启不产生重复记录。旧版已保留的 `release.json` 可补录为成功记录，时间取原校验完成时间，未知耗时显示「—」。

正式部署运行 `python3 scripts/deploy.py --description '本次部署说明'`。脚本使用 Java 21 完整构建和测试，通过 Workbench 上传并校验 SHA-256，备份旧程序和 H2，保留配置后重启服务，再使用已有 Alfred 本地令牌验证公网 HTTPS、认证拦截、授权查询及部署记录。验证失败恢复旧程序；不自动回退数据库或清除业务数据。记录位于 `/var/lib/leon-lite-workflow/deployments/`，由 `LEON_DEPLOYMENTS_DIRECTORY` 指定；`LEON_DEPLOYMENT_ID` 标识当前运行部署。时间版本号使用 `yyyy.MM.dd.HHmmss`，精确程序包由提交号、工作区状态和 SHA-256 区分。备份服务器数据时同时备份该目录，H2 SQL 导出不包含这些部署记录。

**网页一键部署。** 管理页登录后，在监控页点击「一键部署」，后台从 `https://github.com/leonHan01/Leon-Lite-Workflow.git` 的 `main` 分支拉取代码，以独立的 `leon-workflow-build` 账户运行 Java 21 / Maven `clean package`（跳过测试执行），完成备份后更新正式服务。Git 协议受限时，通过 GitHub 官方 API 解析 main 提交号，再下载该提交的官方源码归档，全程验证 HTTPS，不使用第三方镜像。页面自动显示排队、拉取、构建、更新、验证及最终结果，部署期间禁止重复触发。构建失败不替换当前服务，部署或验证失败恢复旧程序，保留数据库及私有配置。若远程分支尚未包含部署控制代码，会在替换服务前拒绝发布，避免移除管理功能。

`POST /api/monitor/deploy` 仅接受已登录管理页的 Cookie、准确的 `Origin` 和 `X-Requested-With`，插件 Bearer 令牌不能直接触发部署；客户端不能指定仓库、分支或命令。请求通过持久化目录通知 `leon-workflow-deploy.path`，由独立的 `leon-workflow-deploy.service` 执行 `/opt/leon-workflow-deploy/` 中 root 所有的固定脚本，应用本身不获得任意 sudo 权限。构建日志仅保存在 root 可读的 `/var/log/leon-workflow-deploy/`，页面只展示固定进度信息。新程序使用临时会话验证公网认证和授权 Alfred 查询，会话用完立即撤销，不向构建进程传递应用凭据。首次安装通过 Workbench 上传并校验脚本和 Maven 包，再运行 `scripts/install_deploy_worker.py --staging <临时目录>`；保留已有代理及配置，启用 `LEON_DEPLOY_ENABLED` 和 `LEON_DEPLOY_SELF_VERIFY`。后台被中断时，由 `ExecStopPost` 恢复并记录结果。

**登录。** 管理页与插件、Alfred 使用同一个访问令牌。默认勾选「永久记住登录」：服务端会话不设到期时间；浏览器通过 `Secure`、`HttpOnly`、`SameSite=Lax` 的 Cookie 保存随机会话凭证，关闭浏览器后仍然有效。持久 Cookie 为 400 天，调用管理接口时自动续期。浏览器可能限制 Cookie 的保存时间，或清除 Cookie。取消勾选时使用浏览器会话 Cookie，服务端最长保留 12 小时。原始令牌不写入 localStorage 或 sessionStorage；服务端保存会话摘要和有效期。会话保存在 H2 中，升级重启不会丢失。主动退出、清除 Cookie 或更换访问令牌后需要重新登录。升级前创建的 30 天会话保持原有效期，重新登录后采用新设置。共享设备使用后点击右上角「退出登录」。

部署时，将 `/workspace/` 静态资源与其它管理页面放在同一套 HTTPS 登录保护之后。

### 声屿在线歌曲

在 `/music.html` 上传 MP3、M4A、AAC、WAV、AIFF、CAF 或 FLAC，单首最多 128 MB，多首依次上传。歌曲名默认取文件名，艺术家和专辑可在上传时填写。浏览器读取可识别格式的时长；声屿客户端下载后补充音频标签、封面和实际时长。管理页提供搜索、试听及确认后删除。删除同时移除服务器音频文件，客户端已缓存的离线副本保留。

在线歌单由服务端保存，Mac 与 Android 客户端每次刷新曲库时同步。在管理页创建或重命名歌单；上传时可指定歌单，也可在曲库勾选歌曲后批量加入目标歌单。一首歌可属于多张歌单。查看某张歌单后可移出歌曲；删除歌单只删除归属，不删除曲库音频。删除歌曲会自动清理所有歌单中的引用。

客户端在「在线歌单」中选择具体歌单，点击播放全部或某首歌曲，按歌单顺序建立队列。断网后保留上次歌单列表，只有已缓存的歌曲可以播放。在线歌单和各设备原有本地歌单分别保存，沿用同一套 token 权限。

接口沿用现有认证：客户端使用 Bearer 令牌，管理页使用 HttpOnly Cookie；Cookie 写请求仍验证准确 Origin 和 `X-Requested-With`，不会公开未认证的音乐接口。

| 接口 | 行为 |
| --- | --- |
| `GET /api/music/tracks` | 返回歌曲 JSON 数组，含稳定 UUID、标题、艺术家、专辑、格式、时长、字节数、SHA-256 和上传时间 |
| `POST /api/music/tracks` | multipart 上传单个 `file`，可附带 `title`、`artist`、`album`、`duration`；成功返回 201 |
| `GET /api/music/tracks/{id}/audio` | 认证后下载音频，支持 Range 请求和浏览器试听 |
| `DELETE /api/music/tracks/{id}` | 删除元数据及服务器文件，成功返回 204 |
| `GET /api/music/playlists` | 返回在线歌单数组，每张包含 UUID、名称、有序 `trackIds` 和创建/更新时间 |
| `GET /api/music/playlists/{id}` | 查看一张在线歌单 |
| `POST /api/music/playlists` | JSON `{ "name": "通勤" }`，创建歌单并返回 201 |
| `PUT /api/music/playlists/{id}` | JSON `{ "name": "新名称" }`，重命名歌单 |
| `POST /api/music/playlists/{id}/tracks` | JSON `{ "trackIds": ["UUID"] }`，按顺序批量加入，自动去重；单次最多 1000 首 |
| `DELETE /api/music/playlists/{id}/tracks/{trackId}` | 仅移出歌单，返回更新后的歌单 |
| `DELETE /api/music/playlists/{id}` | 仅删除歌单及归属，返回 204 |

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
