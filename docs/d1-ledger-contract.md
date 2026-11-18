# D1 通用台账冻结合同

状态：阶段 1 合同已由 leader 与 architect 审阅，2026-11-15 冻结。仅定义标准工装档案，不包含借还、占用、归还或在线设计器。公共 Java 接口位于 `ledger` 和 `authorization.ApplicationActionAuthorization`；旧 EntityDefinition、查询和 RegisteredEntityAction 接口保持兼容。

## 1. 路径和 wire 协议

共同键：`appKey=tooling`、`entityKey=tool`、权限 `tool.read/create/update`。客户端不可传权限、表名、列名、tenant、actor 或执行策略。

| 方法/路径（基址 /api/v1/apps/{appKey}/entities/{entityKey}） | 输入 | data |
|---|---|---|
| GET /definition?use=READ\|CREATE\|EDIT | use 必填，枚举白名单 | `{definition: LedgerDefinition, use, effectiveActions:[canonicalPermission]}` |
| POST /query | `{definitionVersion, query:{filterAST,sort,page,size,fields}}`，query 沿用 EntityQueryContract.Request | `{records:[RecordData],page,size,total,hasMore}` |
| GET /records/{id} | UUID id | RecordData |
| POST /records | `{definitionVersion,values:{...}}` + UUID Idempotency-Key | `{commandId,id,version,definitionVersion,eventId,occurredAt,replay}` |
| POST /records/{id}/update | `{definitionVersion,expectedVersion,values:{...}}` + UUID Idempotency-Key | 同上 |
| POST /references/{referenceKey}/query | `{definitionVersion,use,search,context:{...},size}` | `{options:[{id,label}],hasMore}` |

成功统一 `{data,meta:{traceId}}`。写入仅返回 CommandData 元信息，不附业务字段；有 read 许可时前端再 GET 详情。create-only/update-only 不因成功写入获得读权。query.total 在同一 RO/RR 事务中以与列表完全相同的 tenant+visible predicate 计数。RecordData 为 `{id,version,definitionVersion,values:{...},effectiveActions:[canonicalPermission]}`，ID 与版本不放入可编辑 values。时间 ISO-8601 UTC，UUID 规范字符串；page 从 1 开始，size 省略归一化为 20，仅接受 20/50/100；引用 size 默认 20、最大 50，search 最大 200。默认列表按 id 稳定排序，前端只声明已注册的查询字段及操作符；服务端始终追加 ID 排序。查询 fields 省略使用定义默认列，ID/版本始终作为 RecordData 元信息返回。querySchema.idField 必须为 id，且 querySchema.fields.id 固定 UUID、不可空、selectable/sortable；核心将它映射到 root.id，不要求也不允许业务 fields/fieldColumns 重复登记 id。过滤/排序/projection 可以声明 id，但输出仅在 RecordData.id、不复制进 values；业务布局和所有输入不接受 id。启动验证调用同一个 EntityQueryValidator 验证 schema 白名单，避免注册与执行规则分叉。

Update.values 是该 EDIT 定义接受的完整可编辑表单，不是 PATCH。缺必填拒绝；可选缺值保存 null；不在本次 use 的输入、未知字段、受保护字段均拒绝而非忽略。definitionVersion 是发布契约版本；expectedVersion 是当前记录并发版本。旧 definitionVersion 返回 409 DEFINITION_CONFLICT；旧记录并发版本返回 409 VERSION_CONFLICT；同键异参 409 IDEMPOTENCY_CONFLICT；无权/不可见遵循 403/404；类型/必填/引用输入错误 400/422。错误沿用公共 code/message/traceId/fieldErrors/retryable，HTTP 模式不返回合成结果。

## 2. 发布定义与注册

LedgerDefinition 包含 `appKey,entityKey,definitionVersion,title,fields,inputFields,querySchema,layout,actions,references`。

- fields 是可展示字段及标签/类型/可空/枚举标签；inputFields 独立声明可接受输入及 CREATE/EDIT uses、必填、长度、允许值、引用键。查询 schema 不决定可写字段。查询类型与展示类型固定对应：TEXT/MULTILINE_TEXT→TEXT，UUID/REFERENCE→UUID，INTEGER→NUMBER，DATE_TIME→DATETIME，ENUM→ENUM；不开放 IN_SUBTREE 或未实现的其他查询类型。
- D1 支持 TEXT、MULTILINE_TEXT、UUID、INTEGER、DATE_TIME、ENUM、REFERENCE；长度按 Unicode code points 验证，非法 Unicode 拒绝。INTEGER 为 JSON 安全整数（绝对值 <= 9007199254740991）；DATE_TIME 为带偏移 ISO 时间、最多微秒精度，归一化 UTC。DECIMAL/BOOLEAN/DATE 暂不发布为可支持字段类型，避免声明超出本轮端到端链路。
- layout 有 list/detail/create/edit/query；actions 是 `{read,create,update}`，每项 `{label,permission}`；referenceKey 由输入定义引用，references 只公开标签。路径从已知 app/entity 和标准操作生成，不发布任意 URL。
- 注册为 LedgerBinding Bean，绑定不可变 definition、Storage、ScopePolicy、referenceProviders。重复 app/entity、未知列/标识符、输入与受保护字段交集、声明/映射缺失使启动失败。
- Storage.table/fieldColumns 仅服务器使用。数据库列白名单必须是代码登记的普通标识符；客户端字段先解析到逻辑字段再映射，不能拼客户端 SQL。
- ApplicationContribution 由业务模块提供 appKey/label/nodes，每节点含 id/label/path/entityKey/permissions（允许 read/create/update 的 canonical 权限列表）。导航核心合并受审查的贡献，不要求未来业务修改 APPS/NODES；旧 finding create-only 入口保持。
- 新贡献 route 必须严格为 `apps/{appKey}/entities/{entityKey}`，登记时拒绝不匹配。session bootstrap/menu 的 permissions 投影该成员实际获授的所有 canonical read/create/update 码，而不只菜单 read。Node.permissions 中任一真实获授即可显示入口；create-only 可以进入创建页，不能因此获得 list/detail 权限。
- 前端按钮判断依据当前 session bootstrap/menu.permissions 与 definition.actions 中对应 permission 的交集；definition 只是动作描述，不授予权限。没有 read 时不调用列表/详情；create-only 写入后显示最小成功元信息，有 read 才 GET 详情。后端所有端点仍独立 fresh 鉴权。
- 现有 session OpenAPI 的 applicationId/id 已是 string，无封闭应用 enum；D1 维持通用标识符及旧应用语义。

工具定义的具体字段、scope 与 keeper 模型由业务 lane 注册，本合同不让通用核心识别 tool 名称。

## 3. Storage 和 scope policy 接口

实体表固定技术列：`tenant_id uuid,id uuid,version bigint,created_at timestamptz,updated_at timestamptz,created_by_actor_id uuid,updated_by_actor_id uuid`。业务字段由 fieldColumns 映射。服务端 createDefaults 为代码登记的默认业务字段（例如 state=ACTIVE），derivedFields 声明 policy 可派生字段（例如 organizationId），protectedFields 声明可读不可写业务字段。核心技术列不能被默认值或 policy 结果覆盖。

ScopePolicy.visible(execution) 返回业务可见 predicate；核心自行 AND tenant_id 并保留，不允许 policy 移除。policy 通过 execution.dsl() 使用同一连接；execution.decision() 提供当前 actor/member/organizationIds/observedAt，访问时验证事务归属。

prepareCreate(execution,values) 验证所选 scope 后返回已声明 derivedFields 的值。它可以派生 organizationId，核心不强迫所有未来实体拥有组织字段。requireUpdate(execution,id,current,proposed) 在锁住当前行后验证 keeper、scope/state 稳定性和引用；current/proposed 是完整逻辑字段投影。业务只叠加 keeper/状态规则，不重做身份、RBAC 或会话解析。

ReferenceRegistration 显式登记 allowedUses、contextFields 白名单及 provider。ReferenceProvider.query(execution,request) 由业务实现，use 映射服务器登记 read/create/update 动作；未登记 use 拒绝。context 仅接受该注册声明且属于本 use 可输入字段的键，所有服务器 protected/derived/core 字段均禁止客户端注入；若未来 EDIT 需要当前 record 上下文，必须由已授权记录加载，不能信客户端代传。

D1 的 scope 引用只登记 CREATE、contextFields 为空，不开放 EDIT record context。候选经当前 CREATE 范围和 keeper 过滤；保存时 ScopePolicy 再验证存在、租户和责任，不把曾在候选出现视为授权证明。

## 4. 事务、授权和原子写入

只读使用真实 RO/RR Spring 事务及 HumanSessionAuthorityTransaction.withRead；写入使用 RW/RC，先 AuthorizationWriteGuard.forBusinessCommand，然后 withGuarded。新 ApplicationEntityAction 带代码登记 appKey；fresh actor/account/member/credential、应用 entitlement、RBAC/组织范围复用现有身份和授权仓储。Decision 只能由 authorization 包中的 issuer 创建；每次跨模块访问及 SQL 执行前后验证 issuer、当前 thread、ConnectionHolder、物理 connection 和 borrowed boundary，禁止构造或跨事务使用。

不启用 sensitive authority，不把缺失控制行当 OFF。旧 enum/safety 入口保留；新动作读取自己的 appKey。DefinitionData.effectiveActions 发布本次真实获授的 canonical 权限，静态 actions 不是凭证。RecordData.effectiveActions 发布当前记录可执行的动作：详情在同一 RO/RR 中另外以 UPDATE 的只读 preview 决策和 action-specific ScopePolicy.visible 检查当前行/keeper/状态；必要时 requireUpdate(current,current) 做纯规则检查。列表只展示详情入口，不为每行执行 UPDATE preview；GET 详情才返回编辑能力。preview 只供呈现，写操作仍锁当前行后 fresh 独立验证。

REPLAY 前仍检查当前授权、业务可见性与 keeper，不因回执存在绕过撤权。动作定义不是授权凭证。

写入顺序：root→tenant 授权锁→逻辑幂等键锁→锁当前实体（update）→完整校验→root INSERT/UPDATE→audit→revision+LedgerChangeFactV1→completed receipt。任何一步失败整笔回滚。所有 ID 与时刻由服务器产生；root 的 version create=1/update=expected+1；所有新对象关系同 tenant。

幂等锁独立 two-int namespace `0x4D53464C`，第二个键取租户/actor/account/member/app/entity/action/Idempotency-Key 规范字符串 SHA-256 前四字节 signed int，碰撞仅保守串行化；锁位于权限 guard 后、业务行锁前。租户+actor+account+member+app+entity+action+Idempotency-Key 唯一。同账号退出租户后重新加入得到不同 memberId，不能命中旧成员回执。请求 hash 对规范 JSON 做 SHA-256，含 action、recordId（update）、definitionVersion、expectedVersion（update）及完整输入，不含 traceId。

## 5. V27 共享修订与回执 DDL 合同（业务 lane 独占迁移）

`ledger_record_revision`：

| 列 | 类型/约束 |
|---|---|
| tenant_id, app_key, entity_key, record_id, version | uuid/varchar(64)/varchar(64)/uuid/bigint；联合主键，version>=1 |
| command_id, event_id | uuid，UNIQUE(tenant_id,command_id)，UNIQUE(tenant_id,event_id) |
| definition_version, action | varchar(64)，action CREATE/UPDATE |
| audit_event_id | uuid，FK(tenant_id,audit_event_id) → audit_event(tenant_id,id) |
| actor_id, account_id, member_id, occurred_at | uuid/uuid/uuid/timestamptz，服务端授权事实 |
| snapshot, change_fact | jsonb；snapshot 为完整 RecordData.values，change_fact 为 LedgerChangeFactV1 |

`ledger_command_receipt`：

| 列 | 类型/约束 |
|---|---|
| tenant_id, actor_id, account_id, member_id, app_key, entity_key, action, idempotency_key | uuid/uuid/uuid/uuid/varchar(64)/varchar(64)/varchar(16)/uuid，联合主键 |
| command_id | uuid，UNIQUE(tenant_id,command_id) |
| record_id, result_version | uuid/bigint，result_version>=1 |
| request_hash | char(64)，小写十六进制 SHA-256 |
| definition_version, result, completed_at | varchar(64)/jsonb/timestamptz；result 仅保存最初 CommandData 元信息（replay=false），不保存 RecordData 或业务字段 |

两表全部列 NOT NULL；tenant RLS + FORCE RLS；receipt/revision 不得 UPDATE/DELETE/TRUNCATE，只追加。必须建立 receipt→revision 精确物理外键：receipt 的 `(tenant_id,app_key,entity_key,record_id,result_version,command_id,actor_id,account_id,member_id,action,definition_version)` 引用 revision 的 `(tenant_id,app_key,entity_key,record_id,version,command_id,actor_id,account_id,member_id,action,definition_version)` 唯一键，绑定成功结果、动作、定义版本及主体身份；result 的 id/version/commandId/eventId 必须从同一条 revision 生成，不接受客户端或其他 revision 值；不能只引用 commandId 或依靠应用匹配。revision 的 audit_event_id 以同 tenant 物理 FK 引用 audit_event。不能用跨实体 polymorphic UUID 假装有具体实体表外键。runtime 只获两表 SELECT/INSERT；技术主数据表同 tenant FK 由具体业务 lane 定义。root→audit→revision→receipt 同一 datasource/事务。

LedgerChangeFactV1 完整字段见 Java record：eventId、固定 eventType=`ledger.record.changed`、schemaVersion=1、app/entity/tenant/record/version、definitionVersion、action、commandId、actor/account/member、occurredAt、snapshot。eventId 由服务器每个成功命令只分配一次，重放复用。此阶段只证明事实随修订持久化；没有真实消费者时不注册虚假 Modulith target/no-op listener、不另建 outbox、不声称投递、ACK 或恢复验收。D8 才接真实投递。

## 6. 阶段 1 验证记录

接口冻结时，编译、定义/DTO/OpenAPI 一致性和通用校验测试已通过：Protocol 2、Binding 5、Input 5、Module 1，合计 13 项，无失败或跳过；architect 判定接口阶段 CLEAR。该记录只证明当时接口阶段，不替代 D1 整体出口；后续证据见 [D1 验收记录](d1-closure-verification-2026-11-15.md)。

## 7. Runtime 实现边界与验证入口

通用实现位于 `ledger.LedgerRuntime`、`LedgerRepository`、`LedgerController`。运行器在真实事务绑定连接上先执行 transaction-local `set_config('app.tenant_id', ..., true)` 并读取 `current_setting` 核验，然后才进入 identity-owned borrowed boundary。写入先获取 `AuthorizationWriteGuard.forBusinessCommand`；读使用 RO/RR，写使用 RW/RC。borrowed owner 和每次 SQL 前后检查同线程、同连接、事务状态与 tenant；连接归还前沿既有 owner 校验，RLS local setting 随事务结束恢复。禁止加入已有调用方事务。

DAO 的实体表固定别名 `root`，策略只能增加业务 predicate，tenant 条件由核心独立 AND。现有 EntityQueryValidator 负责输入白名单、页码/尺寸与 ID tie-break 排序；标准台账启动校验额外拒绝尚未实现的 IN_SUBTREE。JDBC 的 timestamptz 无类型投影可返回 `java.sql.Timestamp`，DAO 将其与 OffsetDateTime 一并归一化为 Instant。唯一约束冲突统一返回 409 COMMAND_CONFLICT；其他数据库失败返回 503 DEPENDENCY_UNAVAILABLE，响应不包含数据库异常文本或 SQL。fieldErrors 沿用共享 `{field,reason}`。

首轮定向验证包含 LedgerProtocolTest、LedgerInputValidatorTest、LedgerBindingValidatorTest、LedgerTransportAndJdbcTest、ApplicationActionAuthorizationTest、NavigationServiceTest、NavigationControllerTest 和 ModuleArchitectureTest。真实 PostgreSQL 的 root/revision/audit/receipt 原子性、keeper/scope/replay/concurrency 矩阵由 tooling 集成测试验证；独立浏览器链路及全量回归是最终集成门槛，不能由这些单测或 skipTests jar 替代。
