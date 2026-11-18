# G007 身份生命周期 HTTP 源码检查点 — 2026-11-03

最终状态：freeze3 已重新通过定向 347 项、完整默认 786 项及兼容配置 841 项源码验证。此前 freeze2 的 344/783/838 结果作为历史证据保留，没有用旧结果覆盖后续 deferred SecurityContext 修复。

本批在身份核心提交 `09c47159c81c717348544c2a01eec56656fbf874` 上接入后端 HTTP 与会话边界。它不代表 G007 完成，也不代表真实 PostgreSQL、HTTPS、会话存储、投递、浏览器或桌面验收。V19 未应用，本批没有新增或执行迁移、授权、账号、密钥或外部投递变更。

## 接入行为

| POST 路由 | 接入结果与边界 |
|---|---|
| `/api/v1/account-invitations` | 当前认证与 CSRF 后交给既有核心签发；新签发 201，精确重放 200。响应只有邀请 ID、版本、到期时间和已记录的投递状态，不返回令牌、地址或新账号。 |
| `/api/v1/account-invitations/{id}/revoke` | 保持认证、CSRF、当前授权和版本条件；返回 200。 |
| `/api/v1/account-invitations/{id}/reissue` | 保持认证、CSRF、原发行人和当前两项权限；新签发 201，精确重放 200。 |
| `/api/v1/auth/password-reset-requests` | 匿名可用但仍须 CSRF；接纳后固定 202 和 `{"status":"ACCEPTED"}`，不返回账号、地址、挑战或投递线索。资源缺失和全局限额仍安全拒绝。 |
| `/api/v1/auth/password-resets/consume` | 仅接纳服务端确认的 PASSWORD_RESET 能力，成功或精确已提交重放返回 204；不自动登录。 |
| `/api/v1/auth/invitations/consume` | 仅接纳服务端确认的邀请能力；NEW_ACCOUNT 可匿名，EXISTING_ACCOUNT_MEMBERSHIP 仍需核心验证精确且新鲜的目标账号 Session。成功返回 204。 |

只有后三条精确 method/path 加入匿名许可；管理接口、GET、路径后缀及其他接口没有获得该许可。消费入口通过服务端绑定的 reset/invitation 包装方法，在证明令牌持有后检查不可变存储用途，不能通过客户端 purpose 或另一条路由降低权限。原受锁消费、当前授权和已提交回执顺序未改变。

解析器在服务的来源/全局准入之后，通过惰性 supplier 执行。单次输入最多读取 8193 字节探针，正文上限为 8192 字节；使用严格 UTF-8，不启用字符集自动探测。重复或未知字段、错误根节点/类型、嵌套歧义、尾随值、查询串、不支持的媒体或内容编码、非规范 UUID/key、非法版本和密码/令牌边界均拒绝。消费秘密缓冲区在成功与失败路径擦除；地址和令牌不进入响应或错误文本。网络来源只取可信 servlet remote address，不信任转发头。

功能默认关闭时，Controller 通过 ObjectProvider 得到不可用结果并返回安全 503，不解析请求体、不生成临时密钥，也不破坏已有应用启动。开启功能不等于具备实际密钥、联系人、数据库权限或投递渠道。

## 会话与失败边界

匿名路由策略由 SecurityConfiguration 和凭据代数过滤器共同消费。附带 VerifiedSessionPrincipal 的新能力请求仍执行当前账号查询；只有查询明确证明账号缺失、停用或凭据代数失效，且 Session 清理成功后，才清除认证并以服务器内部标记继续匿名。该路径只过期 SESSION cookie，保留独立有效的 CSRF cookie/header；没有匿名授权降级兜底。账号查询、Session 读取或提交前失效清理失败返回安全 503，处理器不运行。

首次 SecurityContextHolder deferred context 读取也在这三条能力路由的明确依赖边界内：序列化、Session 状态或数据访问等 RuntimeException 导致清除 context、安全 503 和零处理器调用。该捕获没有包住 handler、没有扩大外层持久化过滤器的捕获类型；旧保护路由仍传播原异常。

绝对寿命过滤器仍先执行。能力请求的已过期认证成功清理后不能成为新鲜的既有账号邀请身份；清理失败不会被转换为匿名成功。其他保护路由和既有 csrf/login/logout 行为保持原边界。

六条生命周期 POST 的外层 Session 持久化过滤器注册在实际配置的 Session filter order 减一处，不扩大匿名许可。它只捕获持久化相关 DataAccessException/TransactionException，不重调 chain 或处理器。服务器 handler 标记用于区分处理器前依赖失败与处理器后的保守 OUTCOME_UNKNOWN；201、204 或其他 HTTP 状态不是提交依据。已提交响应继续传播原持久化异常，不伪造替代 JSON。精确原 key/body 重试继续交由核心回执规则判断，不声称原领域写入回滚。

重置领域提交/重放完成后的当前浏览器清理失败不能变成领域回滚响应；后续保护请求仍依赖当前凭据代数。已有账号邀请不会因为 HTTP 接入而被自动登录、替换身份或丢失核心要求的新鲜来源 Session。

## 冻结与验证证据

15 份增量产品源码/测试在复制前后及捕获副本中一致，最终冻结聚合 SHA256：

`dc92d3bf7439c0d3b882f9952b79035688f5710f1a6488128f6ec1bcc043189e`

整个后端构建捕获 456 份实际源码、资源、契约和构建输入；source manifest SHA256：

`4d1f62a96127ec9c9ee41fc9874af640663b529e78e8cf4627ca2e1fc975f066`

环境为固定缓存 Linux arm64 Maven 3.9.11 / Temurin 21.0.9+10，镜像 ID `sha256:3ead0ff36a4b796440e451013a4ce803dbd02f07b6c5b634cc2ad67927dfcc10`。运行使用非 root、只读依赖缓存、离线 Maven、无网络，并设置 `software-factory.test.require-secure-storage=true`；平台能力实跑数量在下文单列。

| 执行 | 实际测试 / 套件 | 失败 / 错误 / 跳过 | 结果 |
|---|---:|---:|---|
| 原 41 定向套件及新解析/HTTP/会话边界三套件 | 347 / 44 | 0 / 0 / 0 | 通过 |
| 完整默认 clean verify，无单元测试过滤或跳过 | 786 / 118 | 0 / 0 / 0 | Maven exit 0 |
| 完整兼容配置 clean verify，`-DskipITs` | 841 / 123 | 0 / 0 / 0 | Maven exit 0 |

计数相互重叠，不能相加。兼容配置是在同一批 786 项默认测试之外增加 55 项既有离线校验。默认构建实际重编译 252 份 main、127 份 test Java；兼容配置为 252 份 main、167 份 test Java，其中 40 份集成源码全部编译。Failsafe 明确跳过 IT，真实 IT XML 为零，不能将编译当作实库执行。Linux 本地存储平台 2 项及适配器 18 项在两轮完整验证中均通过且无跳过。

每个实际 XML 均绑定冻结源码、本轮编译 class 的 SHA256、Java 21 major 65、SourceFile 属性和实际运行 classpath；44 套件显式选择集合与定向 XML 集合完全一致，完整验证包含这 44 套件。分别保留定向/默认各 701 个、兼容配置 855 个编译 class 副本。新测试包括真实 Spring Security 的 web slice 与模拟领域端口、模拟 Session 提交故障边界；它们不提供真实会话存储或 PostgreSQL 证明。

此前 15 文件独立源码审查和新增三文件 deferred-context 窄复审均为 CLEAR；此结论不替代后续实际环境验收。本报告在源码验证后新增或更新，不属于上述产品源码冻结。

本机原始证据：

- 最终冻结：`/tmp/msf-g007-http-source-freeze3-20261103/freeze-manifest.json`。
- 最终验证：`/tmp/msf-g007-http-linux-20261103/attempt3/` 内 targeted、verify、profile-verification-summary.json，源清单、命令、日志、XML 和编译输出副本。
- 前一冻结及通过记录：`/tmp/msf-g007-http-source-freeze2-20261103/` 和 `/tmp/msf-g007-http-linux-20261103/attempt2/`。
- 首轮失败：`/tmp/msf-g007-http-linux-20261103/attempt1/`，未覆盖或删除。

## 保留的失败与修复

首轮执行 343 项 / 44 套件，0 失败、4 错误、0 跳过。其中两项新 HTTP 测试夹具错误分别来自重新设置已有 Answer 时触发空 supplier，以及错误假定 Spring AnonymousAuthenticationToken 为 null；修复使用 doReturn，并明确验证匿名 token 的 principal 不是 VerifiedSessionPrincipal，没有删去安全断言。

另外两项错误由真实默认启动回归引发：Controller 强依赖仅在 enabled 时创建的核心 Service。修复为 ObjectProvider 后，已有两项启动测试恢复通过，并新增关闭功能的实际 HTTP 503/body-trap/零解析回归。产品源码在最终定向和完整验证期间均未再变化。

freeze2 曾实际通过 344/44、783/118、838/123，均无失败、错误或跳过；该冻结聚合为 `38e2b1e7f7d23ffaf9d91287a7ae4d30db2bcf717407e08c89fa6e8cf618bcf5`。之后自查发现，两个过滤器首次读取 deferred SecurityContext 时，非 JDBC 依赖异常仍可能越过原有安全 503 边界。

先将真实旧产品源码和新增回归独立捕获，再实施两处窄修复。旧实现运行 13 项 / 1 套件，0 失败、2 错误、0 跳过；错误分别来自两个新 deferred-context 用例的 SerializationFailedException。该 red 的两份过滤器编译 class 逐字节匹配已验证 freeze2 输出，使用实际 Spring Security 7.1.2 setDeferredContext API 注入故障，没有用模拟替代过滤器实现。它不是实际 Session 存储损坏验收。保留于 `/tmp/msf-g007-http-deferred-red-linux-20261103/attempt1/red-verification-summary.json`；当前依赖字节码检查保留于 `/tmp/msf-g007-http-deferred-context-javap-20261103.txt`。

最终 freeze3 仅在上述两份过滤器和一份测试中继续修改。新回归覆盖 SerializationFailedException、IllegalStateException、DataAccessException 三类、三条能力路由及旧保护路由重抛；随后重新运行本报告所列 347/786/841 三轮，均通过。旧通过记录没有被作为当前修复的验证结果。

## 仍开放的验收

真实 PostgreSQL 的原子性、最小权限/RLS、双向竞争和重启回执；实际 HTTPS 的失效 SESSION 加独立 CSRF 精确重试；真实 SessionRepository 保存故障；实际投递及收件证明；浏览器和桌面交互；本批新接口 OpenAPI/生成客户端接入均未完成。旧核心的密钥保留、验证联系人接入与限流 bucket 保留维护门禁继续存在。

本批没有安装资源、改变目标权限或进行真实投递。上述源码和本地检查不关闭 G007 或整体 P0–P2 验收；提交、PR 发布和线上 CI 由后续发布步骤分别记录。
