# 项目协作要求

- 不要启动游戏验证，游戏运行很卡。
- 用户要求部署或升级服务时，直接完成构建、部署、必要的服务重启和验证，不要重复请求部署确认；如果目标不明确或操作会删除已有数据，先澄清。
- 部署目标为阿里云杭州 ECS `i-bp1dc1ow687ogmpmxc87`，公网地址 `121.40.254.112`。使用 `alibabacloud-workbench-cli` 操作，勿在项目文件或日志中记录云账号凭据和服务密钥。
- 正式服务为 `leon-lite-workflow.service`，程序位于 `/opt/leon-lite-workflow/leon-lite-workflow.jar`，H2 持久化数据位于 `/var/lib/leon-lite-workflow/`。保留数据库及现有配置，部署升级不得清空或覆盖数据；需要回滚时保留旧版程序包。
- 公网使用 HTTPS **443** 端口，地址为 `https://121.40.254.112`。`leon-lite-workflow.service` 当前监听服务器本机 `127.0.0.1:80`；`leon-plugin-tls.service` 对公网提供 443 端口的 HTTPS 代理和 80 端口的 Let's Encrypt 验证路径。`leon-plugin-cert-renew.timer` 负责证书续期，旧的公网 8080 监听已关闭。旧的 `hello-service` 与 `hello-http` 保持停用，不要另行启动旧服务。
- 修改公网端口时，应同步调整 HTTPS 代理、安全组、服务端认证来源、Alfred 地址和 Chrome 插件设置，并验证证书与续期；不要把尚未部署的端口当作现状。插件切换数据库前必须先确认 ID 保留或映射方案，不要清空缓存或覆盖数据。
- 部署时使用 Java 21 构建当前工作区，上传到新的临时路径并校验 SHA-256，再备份当前 JAR、替换并重启正式服务。验证服务和证书续期状态、本机 `/hello`、公网 HTTPS 的认证拦截及授权后的 `/alfred/query`；升级失败要恢复旧程序并核验。保持 H2 数据目录不变。
- 公网插件和 Alfred 接口使用访问令牌，管理页面使用令牌登录并限制写入来源。不要绕过认证或将未认证的管理接口公开；修改访问控制或公开范围时，明确说明实际效果。部署前后不得将凭据、令牌、令牌摘要或敏感数据写进日志、项目文件或版本库。
- 浏览器管理页面通过站内登录页及 `Secure`、`HttpOnly` Cookie 登录，默认记住 30 天；服务端会话保存在 H2 中，退出时撤销。HTTPS 代理用内部 `/auth/check` 保护页面和静态资源，不要恢复会在浏览器关闭后丢失的 Basic 登录弹窗，也不要把原始令牌存入网页 localStorage 或 sessionStorage。
- 正式有道翻译通过 `/etc/leon-lite-workflow-youdao.env`（root 所有、0600）提供 `YOUDAO_APP_KEY`、`YOUDAO_APP_SECRET`，由 systemd 的 `97-youdao.conf` 加载。升级保留该私有配置；检查只输出变量是否已设置，不输出配置内容、完整进程环境或密钥。验证可通过现有 Alfred 客户端请求 `fy hello`、`fy 你好`。
