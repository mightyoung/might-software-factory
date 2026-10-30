# 隐患台账只读切片

状态：契约与无数据库契约测试已建立；HTTP 服务、生产 schema 和身份授权依赖尚未实现。本文件不是可运行端点或业务完成声明。

## 执行计划

1. **先完成 P1 身份边界。** 使用经验证的 `SESSION` 持久会话解析 actor、有效 tenant member 和当前 tenant；配置 CSRF；用服务端授权判断隐患读取权限及数据范围。请求体、query string 和普通 header 都不能选择 tenant。缺会话返回 `401 AUTH_REQUIRED`；会话有效但无读取授权返回 `403 ACTION_DENIED`。
2. **建立隐患只读存储边界。** 由迁移身份新增 `public.finding` 及必要索引、约束与 RLS `ENABLE/FORCE` policy。运行身份保持非 owner、非 superuser、`NOBYPASSRLS`。当前应用角色只在新表上申请 `SELECT`；不授予 DDL、序列写权限或其他库权限。此权限尚未申请或授予。
3. **实现受控查询与详情。** 在 `dualprevention` 提供 jOOQ 只读仓储和 Spring MVC 控制器；从可信身份上下文取得 tenant 和 member，先应用 tenant 与授权数据范围谓词，再算精确总数及分页。详情对不存在和超出数据范围统一返回 `404 RESOURCE_NOT_VISIBLE`。不提供流程命令、附件、导出或写操作。
4. **用两个项目专用库和合成记录验收。** 无登录、无读取权、跨租户 ID、字段/操作符白名单、total/pageCount、排序分页、连接池上下文回收均需负例/正例。没有真实 session、表和经授权运行角色时不启用匿名或伪造身份的替代测试。

## 前后端契约

机器可读契约：[finding-ledger.yaml](../contracts/openapi/finding-ledger.yaml)。所有路径在当前分支都标为 `contract-only`，尚无控制器提供服务。

|用途|方法与路径|请求/响应|
|---|---|---|
|读取当前用户可用 schema|`GET /api/v1/entities/finding/schema`|响应 `data.key=finding`、`data.schemaVersion="1"`、字段清单和 `meta`。|
|列表查询|`POST /api/v1/entities/finding/query`|`filterAST`、`sort`、`page.number`、`size`、可选 `fields`；响应 `data.items`、`data.page` 和 `meta`。|
|详情|`GET /api/v1/entities/finding/{id}`|`id` 为 UUID；响应 `data` 与 `meta`。未找到和不在调用者数据范围内都返回同一 `404 RESOURCE_NOT_VISIBLE`。|

`finding` 是 `logical-schema.md` 的稳定实体 key；字段 schema 版本为字符串 `"1"`。注册字段为 `id, code, description, discoveredAt, sourceEventId, riskId, grade, state, roundNo, version, createdAt, updatedAt`。API 使用 camelCase；不暴露 `tenant_id`。默认列表投影为 `id, code, discoveredAt, grade, state, roundNo`，可选详情字段来自同一 allowlist。`description` 在 v1 可读取，但不可筛选或排序。等级为 `UNKNOWN | GENERAL | MAJOR`；状态只使用接口规范中的 `DISCOVERED | PERSON_CONFIRM_PENDING | SAFETY_CONFIRM_PENDING | SAFETY_DISPATCH_PENDING | RECTIFYING | ACCEPTANCE_PENDING | CLOSED`。

接口设计已有 `GET /entities/{key}/schema` 和 `POST /entities/{key}/query`，没有定义实体详情方法；本切片新增同一命名空间下的 `GET /entities/finding/{id}` 契约，作为独立兼容性 review 项，不暗示既有服务已支持该路由。

列表使用 **1 起始的 offset page**，`size` 只允许 20、50、100，offset 最多 10,000；不使用 cursor。无请求排序时按 `discoveredAt DESC, id ASC`，自定义排序末尾追加 `id ASC` 稳定分页。`total` 是当前有效 tenant、数据范围和用户筛选共同约束后的精确行数，计算在 page slicing 前；`pageCount = ceil(total / size)`，空集两者均为 0。计数不能安全完成时请求失败，不能伪报 0。此业务切片比通用查询 schema 更严格，因为列表 UI 需要可确定的页数。

浏览器使用同源 session cookie `SESSION`，通过浏览器 credential 请求；列表 POST 携带与 `XSRF-TOKEN` cookie 配对的 `X-XSRF-TOKEN`。无效/过期会话为 401；授权或 CSRF 拒绝为 403；详情不存在或超出范围统一 404。服务端不接受 tenant header 或 body tenant 字段。菜单隐藏不能代替接口鉴权。

## 当前阶段依赖与数据库边界

代码检查确认仓库目前没有 `SecurityFilterChain`、身份认证 provider、会话到 tenant 的可信解析器、生产 controller 或生产 migration。P0 集成 migration 只建临时兼容探针表；PR3 记录的受限应用角色在测试库不能读取 `p0_finding_projection`，没有 `finding` 业务表权限。因此本批只验证了 wire schema 与既有查询校验器，不虚称真实 API 或租户隔离完成。

要继续服务端 DB 读实现，最小权限提案为：在两个软件工厂专用数据库已有 schema `USAGE` 前提下，迁移身份创建并拥有 `public.finding`、约束、索引和 RLS policy；应用身份仅获得该表 `SELECT`。不授予应用身份 DDL、`INSERT/UPDATE/DELETE` 或序列权限。真实会话认证、tenant membership、数据范围服务与已有低权限角色的必要读取权限须先就绪；不得使用 request tenant 参数、`SET ROLE`、超级用户或本地数据库替代。

本批没有连接数据库、运行迁移、改现有角色权限、生成正式业务行或增加登录/伪登录接口。剩余实现应在上述依赖通过 review 后开始，并在 `might_software_factory_test` 中使用明确合成且可清理的数据验证真实 HTTP 链路。

## 本地验证记录

- `./mvnw -Dmaven.repo.local=/tmp/might-software-factory-m2 -pl backend -Dtest=FindingLedgerContractTest test`：4 passed，0 failed/errors/skipped。
- `./mvnw -Dmaven.repo.local=/tmp/might-software-factory-m2 -pl backend clean verify`：23 passed，0 failed/errors/skipped；含模块边界、依赖闭包和既有查询校验器检查。
- TDD 初次将测试跑在空契约骨架上时，路径/字段/阻塞声明检查有 3 个断言失败，外部 schema 引用有 1 个解析错误；补齐契约后转为上述通过结果。此红灯是未实现契约的预期证据，不是 HTTP 或数据库功能失败报告。
- 未运行 LAN 数据库 profile；本分支没有可用控制器、生产 finding 表或身份到 tenant 的运行链路。没有在本地创建数据库/容器，也没有连接或修改 LAN 数据库。
