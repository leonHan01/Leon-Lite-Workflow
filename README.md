# Leon Lite Workflow

Java 21 / Spring Boot 的本地 Alfred 工作流服务。保留原项目的 `/alfred/query` 协议以及快捷项、书签、配置、JSON 记录、计算器、时间、Markdown、代码片段、LeetCode 名称和有道翻译功能；原有 `/hello` 也仍可使用。

Leon Tools Pro 中保存的集合网页和工具网址也可直接在 Alfred 中输入关键词检索，回车打开网页。支持按网页或工具名称、网址，以及网页所属集合、分组和备注匹配；多个关键词可组合使用。结果来自当前服务的数据库，修改或删除后会立即反映在检索中。

## 启动

需要 Java 21 和 Maven。macOS 可用 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 选择 JDK。运行 `mvn spring-boot:run`，默认只监听 `127.0.0.1:8080`，管理首页为 `http://127.0.0.1:8080/`。

H2 默认将数据写入运行目录下的 `data/leon-workflow.mv.db`；该目录不提交 Git。`LEON_DB_URL` 可覆盖 JDBC 地址，例如 `jdbc:h2:file:/绝对路径/leon-workflow`。更换工作目录前请注意文件位置。停止服务后备份整个 `data` 目录。`LEON_DB_PASSWORD` 可设置数据库用户密码（不是数据库文件加密）。首次启动会自动创建表。

## Alfred 接口

`POST /alfred/query` 接收 `{"param":"1+2","key":"..."}`，返回原项目格式的结果数组。设置 `LEON_ALFRED_API_KEY` 时，`key` 为原样 `param` 与密钥拼接后的 UTF-8 MD5 小写十六进制，兼容原客户端的签名方式。未设置密钥时跳过签名验证，适合默认的本机监听；管理接口本身没有认证，切勿直接开放到不可信网络。`GET /alfred/statistics` 为兼容保留。

快捷项可在 `/simple-kv.html` 管理，`all` / `all2` 分页，带“首页”动作的项显示在空查询结果。服务内置一组与现有 Alfred workflow 同名的 PNG 图标，并合并当前用户 Alfred workflow 的 `icons` 目录中的图标；可用 `LEON_ALFRED_ICONS_DIR` 修改该目录，同名文件优先使用目录里的版本。管理页面可预览内置图标；Alfred 在客户端读取 workflow 的 `icons` 目录，其他客户端需要将所选 PNG 放入该目录才能显示。

配置编辑页面为 `/diamond.html`，新库可用以下接口创建配置，再在页面修改：

```bash
curl -X POST http://127.0.0.1:8080/api/diamond -H 'Content-Type: application/json' \
  -d '{"diamondKey":"kv-demo","diamondValue":"示例:内容[标签]"}'
curl -X POST http://127.0.0.1:8080/api/diamond -H 'Content-Type: application/json' \
  -d '{"diamondKey":"WebUrlDiamond","diamondValue":"[{\"name\":\"示例 标签\",\"url\":\"https://example.com\"}]"}'
```

`kv-` 开头的配置按行解析 `名称:内容<网址>[标签,标签]{paste}`，可以设置 `defaultIcon` 和 `defaultTags`。`WebUrlDiamond` 使用原书签 JSON 数组格式。JSON 输入会保存记录并返回 `/json-record.html?id=...`，可在 `/json-record-search.html` 搜索。

有道翻译 `fy 内容` 需要设置 `YOUDAO_APP_KEY` 和 `YOUDAO_APP_SECRET`，真实凭据不写入项目源码。阿里云正式服务已通过 `/etc/leon-lite-workflow-youdao.env`（root 所有、权限 0600）配置这两个变量，由 systemd 的 `97-youdao.conf` 加载；升级时保留该文件，不要输出文件内容或进程的完整环境变量。`restart` 命令只提示使用服务管理工具，原服务器专用脚本未迁移。

## 数据迁移

本次迁移代码和表结构，不会读取或自动导入原 MySQL 的个人数据。新 H2 库初始为空；需要原快捷项、配置或 JSON 历史时，先备份原库，再将 `simple_kv`、`leon_diamond`、`json_record` 的数据按对应字段导入新库。迁移后重启服务刷新缓存；原 MySQL 的连接、表和数据不会被修改。注意 H2 与 MySQL SQL 方言不同，不能直接假定 MySQL 整库 SQL 转储可以执行。

## 检查

```bash
mvn test
mvn package
```

## 工作空间页签

管理页面共用一个工作空间外壳，站内切换通过 History API 和 `workspace/app.js` 更新视图。侧边栏与已加载表单保留在当前页面中；刷新和直接打开原有地址仍可用。各视图的内容、事件与样式分别放在 `static/workspace/` 下，避免全局变量和样式互相影响。

部署时应将 `/workspace/` 静态资源纳入与管理页面相同的 HTTPS 登录保护。浏览器回归检查位于 `src/test/browser/workspace-navigation.cjs`，使用模拟数据验证页签状态、快捷项 CRUD、配置保存、JSON 详情和历史导航；运行时通过 `PLAYWRIGHT_MODULE_PATH`、`CHROME_PATH` 指定本机浏览器依赖。

## Leon Tools Pro 插件接口

服务提供原插件的网页集合、分组和网页、便签、待办、工具箱、LLM 会话与消息、插件设置和待办统计接口，保留 `/api` 路径、数字 ID、响应包装和逻辑删除行为。默认地址仍是 `http://127.0.0.1:8080`，与插件现有默认值一致。新 H2 库中的插件表初始为空，并不是原 MySQL 数据的副本；**未完成 ID 保留迁移及核对前，不要将已有插件切换到这个库**，否则缓存中的数字 ID 可能指向错误记录。默认服务仅监听本机且不启用认证；插件接口允许 Chrome 扩展及本机网页跨域访问，包括读取插件设置中的敏感值，请只在可信的本机环境使用。

可设置 `LEON_AUTH_REQUIRED=true` 和 `LEON_AUTH_TOKEN_SHA256`（访问令牌 UTF-8 字节的 SHA-256 十六进制摘要）启用令牌认证，插件和 Alfred 以 `Authorization: Bearer <令牌>` 请求 `/api/**` 和 `/alfred/**`。认证要求开启但摘要缺失或格式错误时服务拒绝启动；缺失或错误令牌返回 401。两个配置未设置时，本机开发环境沿用原有无认证行为。公网必须使用 HTTPS，并在反向代理限制公开的路径；令牌、摘要和敏感配置不要写入日志或版本库。

管理页面使用同一访问令牌在 `/admin-login.html` 登录。默认勾选“永久记住登录”，服务端会话不设到期时间，浏览器通过 `Secure`、`HttpOnly`、`SameSite=Lax` 的 Cookie 保存随机会话凭证，关闭浏览器后仍可登录。持久 Cookie 设置为 400 天，使用管理接口时自动续期；浏览器可能限制 Cookie 的保存时间或清除 Cookie。不勾选时使用浏览器会话 Cookie，服务端最长保留 12 小时。原始令牌不会写入 localStorage 或 sessionStorage，服务端保存会话摘要及有效期。会话保存在 H2 中，升级重启不会丢失；主动退出、清除 Cookie 或更换访问令牌后需要重新登录。升级前创建的 30 天会话保持原有效期，重新登录后采用新设置。共享设备使用后点击右上角“退出登录”。

HTTPS 代理通过内部 `/auth/check` 校验管理页面和静态资源的会话，仅公开登录及退出接口。管理接口支持 Cookie 和原有 Bearer 令牌；Cookie（以及兼容的 Basic）写请求必须来自 `LEON_AUTH_ADMIN_ORIGIN` 指定的准确来源，并携带 `X-Requested-With: XMLHttpRequest`。公网 Cookie 仅通过 HTTPS 发送，页面返回 `Cache-Control: no-store`；浏览器缓存不是登录凭证。Alfred 和 Chrome 插件继续使用原有 Bearer 认证。

定时统计和日结默认关闭。确认数据及配置迁移完成，并停止旧后端对应定时任务后，可设置 `LEON_PLUGIN_DIGEST_SCHEDULING_ENABLED=true` 启用：北京时间 10:00、14:00、20:00 发送统计，00:00 生成日结；DeepSeek 失败时回退为原始统计。手动统计和日结接口不受此开关限制。普通聊天和即时通知仍由插件负责。设置值保存在数据库中，不要把密钥写入日志或文档。

### 插件数据迁移及切换方案

迁移前先确认当前使用的源 MySQL 实例、目标 H2 文件、两边实际表结构、数据量及可用停机窗口。本项目不会自动导入原数据。以下仅为操作方案，**未执行备份、导入或切换**：

已对源项目默认配置指向的 MySQL 做只读元数据检查，九张插件表的列与迁入实体对应；其中 MySQL 的 `BIT`、`DATETIME` 和 `TEXT` 需分别与目标 H2 的布尔、时间和文本类型核对。其他 profile 或实际运行实例仍需在迁移前确认。

1. 停止插件写入和两套服务的定时任务；分别备份源 MySQL 和停服后的目标 H2 文件，并验证备份可恢复。保留浏览器缓存及未同步数据。
2. 在隔离的目标库副本中检查 `web_collection`、`web_collection_group`、`web_collection_item`、`sticky_note`、`todo_item`、`toolbox_item`、`llm_session`、`llm_message` 和 `app_setting` 的实际列、类型、约束和目标数据。源库仅以只读方式导出；不要直接执行 MySQL 整库转储到 H2。
3. 优先逐表按字段转换并保留所有原数字 ID、引用、逻辑删除标记、顺序及时间，包含已删除记录；妥善单独处理 `app_setting` 中的密钥。遇到目标插件表已有记录或同 ID 冲突时停止，不覆盖、不重新分配 ID。先制定并验证可同时更新所有客户端缓存及旧数据引用的映射方案，或使用无冲突的独立目标库。导入后将每张数字 ID 表的 H2 identity 下一个值设为高于该表最大 ID。
4. 对比每表行数、ID 集合、删除状态、父子引用、排序和时间，抽查集合与会话的完整嵌套结果，验证新建 ID 不冲突；用测试配置验证设置和通知，避免打印真实设置值。原库中的孤儿消息应先按源服务行为评估处理，避免额外创建会话改变预期。
5. 在副本上验证插件缓存、旧数据同步、断线失败及 CRUD；确认通过后以备份和验证过的目标库切换服务。旧服务只保留回滚用途，避免双写；确认新端正常后再选择启用新端定时任务。回滚时停止新端任务并恢复切换前的数据和连接配置，先处理切换后的新增写入，避免丢失数据。


## Java 服务端维护约定

- 控制器负责 HTTP 参数、状态码和兼容响应；快捷项写入由 `SimpleKVService` 处理，配置写入及校验由 `DiamondService` 处理，图标文件读取由 `AlfredIconService` 处理。依赖通过构造器显式注入。
- `AlfredRequestGuard` 保留对原始输入的签名校验和全局令牌桶；`AlfredQueryService` 负责处理器排序、有效结果短路、兜底及结果字段补齐。处理器接口只保留实际参与调度的能力。
- `DiamondConfigParser` 只做解析，不读数据库、不更新缓存；配置写入前复用同一解析规则。`AlfredDiamondService` 解析全部配置成功后一次发布快照；解析失败保留上一份结果。快捷项写入提交后刷新缓存，分页在同一缓存引用上完成。
- 网页集合批量读取集合、分组、网页，再由无数据库依赖的 `WebCollectionResponses` 组装响应。LLM 会话列表批量计数，完整详情批量读取消息，避免按每个集合或会话重复访问数据库。写入流程保留原有事务、数字 ID、排序及逻辑删除规则。
- 回归测试覆盖 Alfred 调度及限流、配置解析失败时保留缓存、快捷项增删改后的可见性、批量查询的归属及顺序，以及 H2 排序失败后的事务回滚。修改这些流程时应运行 Java 21 下的 `mvn test`；无需启动游戏验证。

- 计算器使用每次请求独立的 `ArithmeticExpression` 解析器；除零等非有限结果统一显示计算错误。`YoudaoTranslationService` 封装语言选择、V3 签名、表单请求及响应解析，复用 JDK HTTP 客户端类型，移除闲置的有道通用工具和 OkHttp 依赖。翻译词典中的坏项不会丢弃其他合法结果。
- 外部 HTTP 调用保留线程中断信号，日结取消后不再发送回退通知。日结首行统一规范为 `TL;DR：`，摘要限制为 40 个 Unicode 码点；正文保持原样。
- JSON 保存由数据库唯一约束处理并发去重碰撞，冲突后重查已提交的记录；搜索字段优先级和预览来源共用同一份映射。请求中的数字 ID 必须能精确表示为 long，小数、溢出和排序列表里的空 ID 会被拒绝。
