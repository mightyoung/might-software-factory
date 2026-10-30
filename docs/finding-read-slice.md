# 隐患台账只读切片

状态：只读 HTTP handler、服务、finding 查询及 V2 migration 已在专用 `might_software_factory_test` 库实测。身份/成员/组织/角色读取策略和 V3 schema migration 已写入代码；本地密码登录 provider、HTTP 安全接线及 V4 migration 也已形成。V3/V4 未应用到任何数据库，也没有配置身份/授权表运行权限。登录和授权的本地合成数据测试不代表真实数据库会话或生产租户隔离已经验收。

## 本地实现

- `GET /api/v1/entities/finding/schema`：返回 `finding` v1 的字段目录。端点先验证当前会话和服务端读取授权。
- `POST /api/v1/entities/finding/query`：严格解析 JSON；只接受一开始的 offset page，size 为 20/50/100、offset 不超过 10,000。字段、排序、过滤运算符和值由 `FindingEntitySchema` 和 `EntityQueryValidator` 白名单控制。默认排序 `discoveredAt DESC, id ASC`，每行始终含 `id`，响应提供精确 `total` 和 `pageCount`。
- `GET /api/v1/entities/finding/{id}`：详情只按 UUID 读取。不存在与不在可见范围统一为 `404 RESOURCE_NOT_VISIBLE`。

查询范围取自验证过的 `VerifiedSessionPrincipal`，请求 body 和 `X-Tenant-Id` 不参与选租户。授权策略返回当前会话 tenant 与显式组织范围，列表 count、列表和详情查询同时应用 `tenant_id`、`organization_id` 条件。仓储使用参数化 jOOQ 查询，在一个只读 `REPEATABLE_READ` 事务内设置事务级 `app.tenant_id`，以便 RLS 生效；没有动态 SQL 字段名。

## 身份、会话和 CSRF 边界

`POST /api/v1/auth/login` 由 `LocalPasswordAuthenticationProvider` 校验本地密码、账号与 HUMAN principal，并解析所选租户的有效成员后签发 `VerifiedSessionPrincipal`；表单登录、HTTP Basic 和 Spring 默认开发用户均禁用。principal 保存 actor、account、tenant、member ID 及 tenant/member `auth_epoch` 快照；构造器不是 public。每次 finding 读取授权还会按 session actor 查询当前 principal，要求其仍为同 account 的 ACTIVE HUMAN，因此 principal 停用或 actor/account 绑定不符会拒绝旧 session。`credential_version` 尚未纳入 principal 或每请求授权校验；启用改密/重置前必须先实现版本捕获与每请求失效检查。

权限模块的 `EntityReadAccessPolicy` 通过 identity、tenancy、directory 的公开读取接口，以及自身角色授权查询，从 actor principal、账号、租户、成员、当前组织关系、角色授予、动作权限和数据范围读取授权。数据库查询在只读 `REPEATABLE_READ` 事务内设置 `app.tenant_id`，并使用显式租户连接条件。principal 必须按 session actor 对应为 ACTIVE HUMAN，且关联 account 必须等于 session account；账号/租户/成员任一非 `ACTIVE`、session epoch 过期、找不到成员关系、权限缺失或组织范围为空都拒绝；finding 只接受 `finding.read`。`DEPT` 解析当前直接组织成员关系，`DEPT_TREE` 再递归包含有效子组织，`EXPLICIT` 取 `scope_org`；组织授予按成员当前直接组织关系匹配，`SELF` 与 `RELATION` 暂不能表示为 finding 的组织过滤条件，因此不会授予范围。V3 将当前可解释的 scope definition 限制为 `{}`，避免忽略未知 JSON 条件。新 migration 未执行前，相关表不可用会导致请求依赖不可用并拒绝访问。没有伪造用户头、租户头或匿名演示登录路径。

`POST` 受 CSRF 保护，使用 `XSRF-TOKEN` cookie 与 `X-XSRF-TOKEN` header；CSRF cookie 为 Secure、SameSite=Lax，且可由同源浏览器读取。SESSION cookie 使用 `SESSION` 名称并配置 Secure、HttpOnly、SameSite=Lax，Spring Session timeout 为 30 分钟。HTTP 测试通过真实 provider 和合成凭证/成员仓储验证登录、session ID 轮换及停用后拒绝读取；实际 JDBC Session 存储恢复与真实身份表查询仍待数据库联调。

## 数据库 migration 与权限

新增 `backend/src/main/resources/db/migration/V2__dualprevention_finding_read.sql`：`public.finding`、租户内业务号唯一约束、状态/等级/版本检查、读取索引、RLS ENABLE/FORCE 和基于 `app.tenant_id` 的 tenant policy。`organization_id` 是内部数据范围列，不在 API 字段目录中。source event、risk 等跨模块关联目前只有 ID，没有引用到尚未发布的外模块表。

新增 `backend/src/main/resources/db/migration/V3__identity_directory_authorization_read.sql`：身份、成员、组织、岗位/群组、角色、权限和 scope 读取所需的逻辑表及租户 RLS。该脚本仅作为本地 schema 变更，不曾执行；没有账号/角色/权限数据、登录凭证、角色授予变更 API、应用开通读取、授权来源撤销解析器或数据库授权。V3 也未给 `finding.organization_id` 添加组织复合外键，既有 finding 行尚无组织迁移/回填计划。

新增 `backend/src/main/resources/db/migration/V4__identity_password_login.sql`：规范化 login key 索引，以及 `principal`、`credential` 表和租户登录 code 的 SELECT policy。此 migration 只定义结构，不创建账号、密码或授权数据，也未在任何数据库执行。

默认运行配置只使用 `LAN_PG_APP_JDBC_URL` / `LAN_PG_APP_USERNAME` / `LAN_PG_APP_PASSWORD`，并关闭 runtime Flyway。P0 兼容测试 profile 仍显式使用自己的迁移配置与 `flyway_schema_history`；应用 migration 使用独立的 `flyway_schema_history_app`，避免 P0 probe migration 与业务 migration 混为一条历史。

本轮只在 `might_software_factory_test` 执行了 app history 的 version 1 baseline 和 V2 finding migration。测试库已有其他 history 管理的 P0/Session 对象；baseline 只记录现存 schema 基线，不表示 app history 接管这些对象。使用既有测试管理连接执行 migration、合成数据写入和清理，没有改角色或访问其他库。对既有低权限应用角色只执行 `GRANT SELECT ON TABLE public.finding`。未在 dev 库或其他数据库迁移或授权。

- 测试库检查确认应用角色不是 superuser，也没有 `BYPASSRLS`；在测试库具有 `CONNECT`、`public` schema `USAGE` 和 `finding SELECT`，没有 finding 的 `INSERT/UPDATE/DELETE`，finding 表由不同角色拥有。RLS ENABLE/FORCE 与 tenant policy 已核验。该探针没有检查 `CREATEDB`、`CREATEROLE`、角色成员关系、schema `CREATE` 或表 `TRUNCATE` 权限，因此本文件不对这些权限作结论。测试不改变其他库的默认连接权限。
- 该测试使用已有管理身份迁移，因此还没有独立、低权限 migration runner。若后续在其他库执行迁移，应为迁移身份单独配置所需的 `CONNECT`、`public` schema `USAGE/CREATE`；它拥有新表，不作为应用登录身份。运行身份在每个目标库只需 `CONNECT`、schema `USAGE` 及 finding `SELECT`，不需要 finding 写权限、sequence 权限、DDL、`BYPASSRLS`、表所有权或角色成员授权。
- `spring.session.store-type=jdbc` 当前已配置，专用测试库已有 `public.spring_session`；本轮没有给运行身份授予 Session 表权限，也没有验证 Spring Session 序列化或 JDBC 持久化。若后续启用 JDBC 持久会话，需另行核定会话表权限，不能把它混进 finding 的 grant。
- 本只读切片不调用 Flowable。不要给 finding 运行身份 Flowable 表权限；若另阶段启用流程引擎，须按实际命令及引擎版本另列所需对象权限，不以本切片授权代替。

测试仅证明该专用测试库及该既有低权限角色上的数据库行为，不表示生产数据库已迁移或获授权。

## 前后端 wire 契约

机器可读契约：[finding-ledger.yaml](../contracts/openapi/finding-ledger.yaml)。handler 现已在此分支实现，但生产依赖仍 fail closed；OpenAPI `x-implementation-status` 明确为 `local-handlers-fail-closed`。

|用途|方法与路径|前端响应要点|
|---|---|---|
|字段目录|`GET /api/v1/entities/finding/schema`|`data.key=finding`、`data.schemaVersion="1"`、`data.fields`、`meta`|
|分页列表|`POST /api/v1/entities/finding/query`|请求 `filterAST/sort/page.number/size/fields`；响应 `data.items`、`data.page.number/size/total/pageCount`、`meta`|
|详情|`GET /api/v1/entities/finding/{id}`|`data` 为 finding v1 字段；`meta`|

### 已封存整改证据内容

`GET /api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-items/{evidenceItemId}/content` 返回附件流。请求只包含 finding、轮次和 evidence item ID；服务端重新解析当前 finding 读取授权、已封存 submission 快照、当前 file link 撤销状态与 READY 文件状态，再按快照里的 receipt ID 校验信任上下文，并从固定 `storageVersion` 读取。请求不接受 file ID、对象键、路径或版本参数。快照缺失、未封存、链接已撤销或越出当前组织范围时统一隐藏为 404；可信回执或存储版本不可用时返回 503。响应使用 `application/octet-stream`、`attachment`、`no-store` 和 `nosniff`。

本地文件 adapter 默认未启用。只有显式配置私有根目录及至少 256 位的 receipt HMAC 密钥和 key ID 才创建；部署必须预先提供仅服务账户可访问的 POSIX 私有目录和 `.receipt-registry` 子目录，adapter 会校验权限并拒绝不支持 POSIX 权限的文件系统；应用不会创建或扫描根目录。固定版本 `.blob` 必须由服务账户拥有、仅 owner 可读且没有 POSIX 写权限，并且 `unix:nlink` 必须为 1；文件系统不支持读取链接数时拒绝核验/读取。路径由服务端派生为 `root/{tenant UUID}/{file UUID}/{storageVersion}.blob`。核验签发只在业务事务外散列文件并写入 HMAC 回执；业务事务只查现存回执。读取端对返回前的固定版本内容执行摘要校验，单个文件最大 64 MiB、进程内总缓冲预算也是 64 MiB，预算耗尽时 fail-closed。测试使用临时目录里的合成文件，不设置运行配置，不触碰用户文件。该实现没有上传/扫描器，没有执行 V6，也没有实际 PG18 或部署根目录验收。

授权读取事务在确认当前会话/权限、封存快照和未撤销文件链接时持有必要的短期行锁；事务提交是本端点的授权线性化点。若撤权发生在该事务提交之后，已打开的请求仍可能发送其已校验并缓冲的内容；之后发起的请求会重新检查权限和链接并拒绝。此实现不承诺撤权可以中止已在途响应或撤回已发送字节。

当前本地 adapter 只验证一个配置的 HMAC key ID，没有历史验证 keyring。更换或删除该密钥会使仍由它签发的回执无法验证；在增加历史 key ID 验证支持或完成受控回执重签并确认旧快照不再依赖它之前，部署不得轮换/移除该 key。此处没有生成或配置真实密钥。

列表使用接口基线定义的实体级注册查询路径 `/api/v1/entities/{key}/query`。这不是大屏/页面批量查询协调器；后者唯一入口仍是 `/api/v1/page-queries`，其 pageKey、widget 请求拆分、预算、缓存和渐进结果本阶段均未实现。

字段 key 是 `id, code, description, discoveredAt, sourceEventId, riskId, grade, state, roundNo, version, createdAt, updatedAt`。默认投影为 `id, code, discoveredAt, grade, state, roundNo`；`description` 可读但不可筛选或排序。请求体和返回投影都不包括租户/组织控制列。

浏览器使用同源 `SESSION` cookie。列表 POST 要带 `X-XSRF-TOKEN`。无有效会话是 401，读取权限或 CSRF 拒绝是 403，详情不可见统一 404，查询形状错误 400，安全读取失败 503。菜单可见性不代替服务端授权。

## 当前阻塞与验证边界

本地登录 provider 和 `EntityReadAccessPolicy` 已实现，但 V3/V4 未迁移，也未验证实际运行角色对身份/授权表的权限、JDBC Session 持久化或真实身份到租户绑定。应用开通读取、权限/角色数据维护、grant source 注册/撤销解析器、账号初始化/邀请/重置、失败限流/短期锁定及 `credential_version` 会话撤销均未完成。真实 PostgreSQL 15.4 测试库的 finding RLS/query/detail 探针仍使用 test-only principal 和授权策略，不能据此宣称真实身份会话、生产授权或租户隔离已验收。

本地 HTTP 登录测试使用实际 `LocalPasswordAuthenticationProvider` 与合成凭证/租户仓储；授权变化测试使用合成授权快照。jOOQ MockDataProvider 检查查询形状和参数。它们不运行 PostgreSQL parser、RLS policy、连接池回收、真实身份表查询或 JDBC Session 持久化。

固定基础提交 `a10f7e61ac5a7051307ae313279277a9a0006623` 的原工作区测试记录：`./mvnw -pl backend -P lan-postgres-compatibility -Dit.test=FindingPostgresReadIT verify -q` 的 Surefire 35 项与 Failsafe `FindingPostgresReadIT` 1 项通过，失败、错误、跳过均为 0。该记录不覆盖本分支的 V3/V4 或生产 provider。本分支复核后的 `./mvnw -pl backend test -q` 共 69 项通过，失败、错误、跳过均为 0；HTTP 登录使用真实 provider 与合成仓储。兼容 profile 的集成测试源码仅编译命令为 `./mvnw -pl backend -P lan-postgres-compatibility -DskipTests -DskipITs test-compile`，会编译集成测试和共享夹具；这一步不运行集成测试或连接数据库。此分支本地验证不覆盖 V3/V4 的 PostgreSQL 执行、RLS、真实身份表、JDBC Session 或应用运行角色授权。未运行 Redis、媒体服务、视频处理或负载测试；没有推送分支、创建 PR 或改 main。
