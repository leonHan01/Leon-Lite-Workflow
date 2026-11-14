# 项目协作要求

- 不要启动游戏验证，游戏运行很卡。
- 用户要求部署或升级服务时，直接完成构建、部署、必要的服务重启和验证，不要重复请求部署确认；如果目标不明确或操作会删除已有数据，先澄清。
- 部署目标为阿里云杭州 ECS `i-bp1dc1ow687ogmpmxc87`，公网地址 `121.40.254.112`。使用 `alibabacloud-workbench-cli` 操作，勿在项目文件或日志中记录云账号凭据和服务密钥。
- 正式服务为 `leon-lite-workflow.service`，程序位于 `/opt/leon-lite-workflow/leon-lite-workflow.jar`，H2 持久化数据位于 `/var/lib/leon-lite-workflow/`。保留数据库及现有配置，部署升级不得清空或覆盖数据；需要回滚时保留旧版程序包。
- 公网使用 HTTPS **443** 端口，地址为 `https://121.40.254.112`。`leon-lite-workflow.service` 当前监听服务器本机 `127.0.0.1:80`；`leon-plugin-tls.service` 对公网提供 443 端口的 HTTPS 代理和 80 端口的 Let's Encrypt 验证路径。`leon-plugin-cert-renew.timer` 负责证书续期，旧的公网 8080 监听已关闭。旧的 `hello-service` 与 `hello-http` 保持停用，不要另行启动旧服务。
- 修改公网端口时，应同步调整 HTTPS 代理、安全组、服务端认证来源、Alfred 地址和 Chrome 插件设置，并验证证书与续期；不要把尚未部署的端口当作现状。插件切换数据库前必须先确认 ID 保留或映射方案，不要清空缓存或覆盖数据。
- 部署时使用 Java 21 构建当前工作区，上传到新的临时路径并校验 SHA-256，再备份当前 JAR、替换并重启正式服务。验证服务和证书续期状态、本机 `/hello`、公网 HTTPS 的认证拦截及授权后的 `/alfred/query`；升级失败要恢复旧程序并核验。保持 H2 数据目录不变。
- 后续部署统一使用 `python3 scripts/deploy.py --description '本次部署说明'`，自动构建、校验、备份、重启、验证及失败回滚。每次部署把时间、版本、提交号、工作区状态、程序包校验值和结果原子写入 `/var/lib/leon-lite-workflow/deployments/`，供 `monitor.html` 展示。保留历史记录，普通服务重启不得新增部署记录；记录中不得包含凭据、摘要、配置内容或业务数据。
- 每次成功部署验证完成后，仅保留最近 2 次部署的完整备份（JAR、停服后的 H2 快照、部署配置和校验清单），清理更早的备份及构建/上传目录。先校验完整性并保留本次可用回滚包；不足 2 份完整备份、部署失败或回滚中不得清理。保护进行中的构建/上传，保留 Maven 缓存、Git 仓库、全部部署记录及现有业务数据。命令行上传目录统一使用 `/opt/leon-lite-workflow/staging/`，同步更新服务器 `/opt/leon-workflow-deploy/` 中的保留策略。
- 监控页一键部署固定拉取 GitHub `leonHan01/Leon-Lite-Workflow` 的 `main`，由 `leon-workflow-deploy.path` 触发独立后台任务。Git/Maven 以非特权构建账户执行，root 所有的固定部署脚本只负责备份、替换、重启与回滚。不得给应用开放任意命令或 sudo 权限，触发接口必须校验管理页 Cookie 和写入来源。修改后台脚本后，需通过 Workbench 校验上传并同步 `/opt/leon-workflow-deploy/` 中的安装副本。
- 公网插件和 Alfred 接口使用访问令牌，管理页面使用令牌登录并限制写入来源。不要绕过认证或将未认证的管理接口公开；修改访问控制或公开范围时，明确说明实际效果。部署前后不得将凭据、令牌、令牌摘要或敏感数据写进日志、项目文件或版本库。
- 浏览器管理页面通过站内登录页及 `Secure`、`HttpOnly` Cookie 登录，默认记住 30 天；服务端会话保存在 H2 中，退出时撤销。HTTPS 代理用内部 `/auth/check` 保护页面和静态资源，不要恢复会在浏览器关闭后丢失的 Basic 登录弹窗，也不要把原始令牌存入网页 localStorage 或 sessionStorage。
- 正式有道翻译通过 `/etc/leon-lite-workflow-youdao.env`（root 所有、0600）提供 `YOUDAO_APP_KEY`、`YOUDAO_APP_SECRET`，由 systemd 的 `97-youdao.conf` 加载。升级保留该私有配置；检查只输出变量是否已设置，不输出配置内容、完整进程环境或密钥。验证可通过现有 Alfred 客户端请求 `fy hello`、`fy 你好`。

- 工作流脚本由 `/usr/bin/setpriv` 清除继承能力后，在 `/usr/bin/bwrap` 隔离进程内运行，保留非特权用户命名空间、现有服务限制和清空子进程环境的策略。凭据主密钥由 `/etc/leon-lite-workflow-workflows.env`（root 所有、0600）提供 `LEON_WORKFLOW_CREDENTIAL_KEY`，systemd 的 `96-workflows.conf` 加载；升级保留原值，不重新生成或直接轮换已有密钥，不输出密钥或摘要。安装使用 `scripts/install_workflow_runtime.py`，该脚本只配置运行环境及受保护的代理路由，服务重启由部署脚本统一完成。
- `/workflows.html` 与 `/workspace/` 资源使用管理页 Cookie 登录保护，`/api/workflows/**` 由服务端校验管理会话及写入来源，插件 Bearer 令牌不能直接管理或运行工作流。部署脚本在线执行 HTTP → JavaScript → Groovy 验收（包括加密凭据读取），成功后仅清理本次创建的测试流程、测试凭据及临时登录会话；不得删除既有流程或凭据。
