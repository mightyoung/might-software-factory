# 隐患台账只读切片

状态：只读 HTTP handler、服务、finding 查询及 V2 migration 已在专用 `might_software_factory_test` 库实测。身份/成员/组织/角色读取策略和 V3 schema migration 已写入代码，并通过本地 backend 单元与契约测试。V3 未应用到任何数据库，也没有配置其运行时读取权限。生产登录签发仍未实现；本文件不表示生产闭环或真实租户隔离已经验收。

## 本地实现

- `GET /api/v1/entities/finding/schema`：返回 `finding` v1 的字段目录。端点先验证当前会话和服务端读取授权。
- `POST /api/v1/entities/finding/query`：严格解析 JSON；只接受一开始的 offset page，size 为 20/50/100、offset 不超过 10,000。字段、排序、过滤运算符和值由 `FindingEntitySchema` 和 `EntityQueryValidator` 白名单控制。默认排序 `discoveredAt DESC, id ASC`，每行始终含 `id`，响应提供精确 `total` 和 `pageCount`。
- `GET /api/v1/entities/finding/{id}`：详情只按 UUID 读取。不存在与不在可见范围统一为 `404 RESOURCE_NOT_VISIBLE`。

查询范围取自验证过的 `VerifiedSessionPrincipal`，请求 body 和 `X-Tenant-Id` 不参与选租户。授权策略返回当前会话 tenant 与显式组织范围，列表 count、列表和详情查询同时应用 `tenant_id`、`organization_id` 条件。仓储使用参数化 jOOQ 查询，在一个只读 `REPEATABLE_READ` 事务内设置事务级 `app.tenant_id`，以便 RLS 生效；没有动态 SQL 字段名。

## 身份、会话和 CSRF 边界

`SessionContextResolver` 只接受 identity 模块签发的 principal 类型；无认证或普通字符串 principal 返回 `401 AUTH_REQUIRED`。principal 保存 actor、account、tenant、member ID 及 tenant/member `auth_epoch` 快照；构造器不是 public，唯一显式构造夹具位于 test source。生产登录/身份验证 provider 尚未实现，Security 配置禁用 form login、HTTP Basic 和默认生成用户，因此当前没有生产途径创建此 principal。

权限模块的 production `EntityReadAccessPolicy` 通过 identity、tenancy、directory 的公开读取接口，以及自身角色授权查询，从账号、租户、成员、当前组织关系、角色授予、动作权限和数据范围读取授权。数据库查询在只读 `REPEATABLE_READ` 事务内设置 `app.tenant_id`，并使用显式租户连接条件。账号/租户/成员任一非 `ACTIVE`、session epoch 过期、找不到成员关系、权限缺失或组织范围为空都拒绝；finding 只接受 `finding.read`。`DEPT` 解析当前直接组织成员关系，`DEPT_TREE` 再递归包含有效子组织，`EXPLICIT` 取 `scope_org`；组织授予按成员当前直接组织关系匹配，`SELF` 与 `RELATION` 暂不能表示为 finding 的组织过滤条件，因此不会授予范围。V3 将当前可解释的 scope definition 限制为 `{}`，避免忽略未知 JSON 条件。新 migration 未执行前，相关表不可用会导致请求依赖不可用并拒绝访问。没有伪造用户头、租户头或匿名演示登录路径。

`POST` 受 CSRF 保护，使用 `XSRF-TOKEN` cookie 与 `X-XSRF-TOKEN` header；CSRF cookie 为 Secure、SameSite=Lax，且可由同源浏览器读取。SESSION cookie 使用 `SESSION` 名称并配置 Secure、HttpOnly、SameSite=Lax，Spring Session timeout 为 30 分钟。身份登录轮换和实际持久会话恢复仍待真实身份 provider 集成。

## 数据库 migration 与权限

新增 `backend/src/main/resources/db/migration/V2__dualprevention_finding_read.sql`：`public.finding`、租户内业务号唯一约束、状态/等级/版本检查、读取索引、RLS ENABLE/FORCE 和基于 `app.tenant_id` 的 tenant policy。`organization_id` 是内部数据范围列，不在 API 字段目录中。source event、risk 等跨模块关联目前只有 ID，没有引用到尚未发布的外模块表。

新增 `backend/src/main/resources/db/migration/V3__identity_directory_authorization_read.sql`：身份、成员、组织、岗位/群组、角色、权限和 scope 读取所需的逻辑表及租户 RLS。该脚本仅作为本地 schema 变更，不曾执行；没有账号/角色/权限数据、登录凭证、角色授予变更 API、应用开通读取、授权来源撤销解析器或数据库授权。V3 也未给 `finding.organization_id` 添加组织复合外键，既有 finding 行尚无组织迁移/回填计划。

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

列表使用接口基线定义的实体级注册查询路径 `/api/v1/entities/{key}/query`。这不是大屏/页面批量查询协调器；后者唯一入口仍是 `/api/v1/page-queries`，其 pageKey、widget 请求拆分、预算、缓存和渐进结果本阶段均未实现。

字段 key 是 `id, code, description, discoveredAt, sourceEventId, riskId, grade, state, roundNo, version, createdAt, updatedAt`。默认投影为 `id, code, discoveredAt, grade, state, roundNo`；`description` 可读但不可筛选或排序。请求体和返回投影都不包括租户/组织控制列。

浏览器使用同源 `SESSION` cookie。列表 POST 要带 `X-XSRF-TOKEN`。无有效会话是 401，读取权限或 CSRF 拒绝是 403，详情不可见统一 404，查询形状错误 400，安全读取失败 503。菜单可见性不代替服务端授权。

## 当前阻塞与验证边界

生产登录/身份 provider、应用开通读取、权限目录/成员/角色数据种子与变更流程、grant source 注册/撤销解析器、独立 migration 执行，以及运行身份对身份/授权表的最小 `SELECT` 权限均未完成。新增授权策略的单元和契约测试已在本地通过；真实 PostgreSQL 15.4 测试库的既有 RLS/query/detail 集成探针仍使用 test-only principal 和测试授权策略，没有验证 V3、真实身份到租户绑定或 HTTP session。因此不能据此宣称生产用户已获授权或租户隔离已经验收。

无数据库检查使用 test-only 安全上下文夹具、显式 fake repository/policy 和 jOOQ MockDataProvider。已有测试证明 HTTP 状态码、CSRF/输入白名单、tenant/org 参数绑定和 SQL 形状，不运行 PostgreSQL parser、RLS policy、连接池回收或真实身份会话。

固定基础提交 `a10f7e61ac5a7051307ae313279277a9a0006623` 的原工作区测试记录：`./mvnw -pl backend -P lan-postgres-compatibility -Dit.test=FindingPostgresReadIT verify -q` 的 Surefire 35 项与 Failsafe `FindingPostgresReadIT` 1 项通过，失败、错误、跳过均为 0。集成测试检查同一低权限连接的列表与详情、跨租户行级策略和连接内 tenant context 清理，并清理后确认合成行数为 0。该记录不覆盖本分支的 V3 或授权 resolver。本分支 backend 单元测试命令 `./mvnw -pl backend test -q` 共 47 项通过，失败、错误、跳过均为 0。兼容 profile 的集成测试源码仅编译命令为 `./mvnw -pl backend -P lan-postgres-compatibility -DskipTests -DskipITs test-compile`，成功生成 `backend/target/test-classes/com/mightyoung/softwarefactory/FindingPostgresReadIT.class`；这一步没有运行集成测试或连接数据库。两项本地验证均不覆盖 V3 的 PostgreSQL 执行、RLS、真实身份会话或应用运行角色授权。未运行 Redis、媒体服务、视频处理或负载测试；没有推送分支、创建 PR 或改 main。JDBC Session 持久化和生产身份仍未通过真实数据库验证。
