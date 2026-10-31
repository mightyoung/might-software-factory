# 前端与后端 OpenAPI 契约差异清单（隐患台账 / 上报）

状态：本地切片以远端 `dev` `6b0c2ee55741f9dd610742dd0e277288534ac432` 的源码树为基线，增加 session、reportable organizations 和 finding.create 的最小服务端闭环契约与实现。后端 Surefire 162 项通过，LAN 集成测试源码编译通过；前端 API 类型由锁定的生成器生成，`check:api`、TypeScript typecheck 与 Vitest 176 项通过。以上均为本地/合成验证，**本切片未连接 PostgreSQL，也未进行真实 API 或前后端联调**。前端仍使用合成适配器，没有 HTTP 适配器。

本轮变更：新增 `session-api.yaml`；`/api/v1/session/current` 复核账号、租户、成员状态与授权纪元；`/api/v1/organizations/reportable` 只返回当前会话 `finding.create` 权限范围内的有效组织；创建由服务端按租户和上海提交日生成 `YH-YYYYMMDD-NNNN`，响应含业务编号，重放读取原隐患编号。已生成 `finding-workflow.ts` 与 `session-api.ts`；生成器依赖锁文件未改。

对照对象：

- `contracts/openapi/finding-ledger.yaml`（只读：字段目录、分页列表、详情）
- `contracts/openapi/finding-workflow.yaml`（写入：创建隐患与流程命令）
- `contracts/openapi/session-api.yaml`（CSRF、登录/注销、当前用户、可上报组织）
- `contracts/openapi/entity-query.yaml`（通用查询请求与错误结构）

## 一、已对齐

| 项 | 契约 | 前端 |
|---|---|---|
| 每页条数 | 20 / 50 / 100 | 20 / 50 / 100 |
| 幂等键 | `Idempotency-Key` 必须是 UUID | `newIdempotencyKey()` 生成 UUID v4 |
| 租户 / 上报人来源 | 只来自服务端会话，请求体不能选 | 上报草稿没有租户、上报人字段 |
| 不存在与无权限的详情 | 统一 404 | `get()` 对不可见记录返回 `null` |
| 状态枚举 | 7 个，含 `DISCOVERED` | 已补齐（原来缺 `DISCOVERED`），测试锁定顺序 |
| 标识与编号 | `id`（UUID）与 `code` 分开 | 已拆分，见 R1 |

## 二、不一致，接入前必须处理

### 读取（台账与详情）

| # | 差异 | 影响 | 建议 |
|---|---|---|---|
| R1 | ~~契约 `id` 是 UUID，业务编号是 `code`；前端把 `DEMO-HZ-001` 同时当主键、路由参数和展示编号~~ **已处理（前端）**：记录拆成 `id`（路由、勾选、读取详情）和 `code`（展示、关键词查询）；勾选框的读屏名称改用 `code`（新增 `recordLabel`），避免朗读 UUID | 合成数据的 `id` 是形如 UUID 的演示值 | 接入真实接口时只需映射字段 |
| R2 | 契约有 `grade`（`UNKNOWN` / `GENERAL` / `MAJOR`）；前端固定显示"待专业评估" | 真实数据会出现等级 | `UNKNOWN` 显示"待专业评估"，其余按契约显示；等级文案需要业务确认 |
| R3 | ~~时间：契约是带时区的 `discoveredAt` / `createdAt` / `updatedAt`；前端是无时区的本地字符串~~ **已处理（前端）**：按后端建议，界面一律按 Asia/Shanghai 显示；记录里的时间是带 +08:00 的 ISO；日期筛选与日期列按上海日期归属；上报表单提示“按北京时间填写” | 这仍是约定：后端说明目前没有已验证的租户时区配置 | 接入时“上报时间”取 `createdAt`，“发现时间”取 `discoveredAt` |
| R4 | 契约列表和详情**没有** `location`、`reporter`、`responsible`、`history` | 台账列"所在位置 / 设备""责任人"、详情时间线没有数据来源 | 需要后端补字段或补接口，否则这几列只能去掉（见"待决定"） |
| R5 | 契约没有 `source`、`urgency`、`interimMeasure`、`backfill` | 详情里这四项没有来源 | 同上 |
| R6 | ~~契约 `description` 不可筛选、不可排序；`code` 只支持 `EQ` / `CONTAINS`~~ **已处理（前端）**：查询框改为“隐患编号”，只按编号匹配，不在已返回的一页上过滤描述 | 后端确认：描述检索与“编号 OR 描述”尚未实现 | 是否开放需单独补契约、校验与性能验证 |
| R7 | 契约分页：请求 `page.number` + 顶层 `size`；偏移量不得超过 10000；响应 `page.number/size/total/pageCount` | 100 条/页时最多约 100 页；超出会 400 | 适配器负责映射；界面要处理 400 |
| R8 | 契约错误：400 / 401 / 403 / 404 / 429（带 `Retry-After`）/ 503，响应体含 `code`、`message`、`traceId`、`retryable`、`fieldErrors` | 前端读取失败只分 `unauthenticated` / `forbidden` / `unknown`，429 与 503 没有区分，也不展示 `traceId` | 接入 HTTP 适配器时一起补；`traceId` 展示给用户便于排障 |
| R9 | 列表 POST 需要 `X-XSRF-TOKEN`（取自同源 `XSRF-TOKEN` cookie），并带同源凭证 | 适配器必须实现 | 在 HTTP 适配器里统一处理 |

### 写入（上报）

`POST /api/v1/findings`（`createFinding`）：

| # | 差异 | 影响 | 建议 |
|---|---|---|---|
| W1 | `organizationId` 仍由创建请求显式提供；新增 `GET /api/v1/organizations/reportable` 返回当前成员 `finding.create` 范围内的有效组织 | 新建表单需单选组织；后端创建时再次校验权限 | 已有后端契约和实现；列表为空时不能创建。不是全租户组织目录 |
| W2 | 创建请求不再接收 `code`；服务端按租户和上海提交日生成 `YH-YYYYMMDD-NNNN`；响应返回 `findingId` 与 `code` | 前端展示服务端编号，幂等重放对应同一条记录和编号 | 已实现事务内序列与回执查找。真实 PostgreSQL 并发/回滚验收待做 |
| W3 | 创建仅接受 `grade: UNKNOWN`，服务端 DTO 校验拒绝 `GENERAL` / `MAJOR` | 上报人不定级 | 契约和合成 Bean Validation 测试已锁定；实际 HTTP/DB 请求未联调 |
| W4 | 请求没有 `location`、`source`、`urgency`、`interimMeasure` | 现有表单的这些值不能持久保存 | **仍未实现**。真实写入切换前须另批匹配数据库、响应和幂等回执；不要塞进描述或静默丢弃 |
| W5 | `description` 长度 1–20000；前端 5–500 | 前端限制更严，属于产品假设 | 保留前端限制，不与契约冲突 |
| W6 | 响应已有 `findingId`、`code`、`state`、`roundNo`、`version`；尚无 `submittedAt` / `backfill` | 成功页不能依据后端显示补报 | 不在前端推断补报；其阈值和持久字段尚未确认，本轮不报告该能力可用 |
| W7 | 错误：400 请求无效、409 版本或幂等键冲突、422 流程规则不满足、503 依赖不可用 | 前端 `FindingSubmitError` 没有"流程规则"这一类 | 增加 `workflow-rule` 类并映射 422；400 对应 `validation` |

### 会话与菜单

| # | 差异 |
|---|---|
| S1 | 新增 `session-api.yaml`，覆盖 CSRF、204 登录/注销、当前会话用户和可上报组织；菜单与权限目录接口仍没有 |
| S2 | 登录、当前用户、组织选择可按新契约接线；菜单和前端 `safety.finding.report` 与后端 `finding.create` 的映射仍待定。类型已生成并通过漂移检查 |

## 三、已有契约、前端尚未使用

`finding-workflow.yaml` 已有后续流程命令：确认（本人 / 安全部门）、派单、提交整改、验收，均要求 `expectedVersion`、`Idempotency-Key`、`X-XSRF-TOKEN`。前端目前这些按钮都是禁用的演示状态；接入时详情必须带 `version`（读取契约已提供）。

## 四、后端自己声明的阻塞（会影响联调时间）

远端交接报告记录 NAS PostgreSQL 18.6 上 V2–V6 已在 `might_software_factory_test` 验证，Finding read probe 通过并清理合成记录；之后 V7 与精确列授权已在另一个隔离库 `might_software_factory_pg18_test` 应用，独立提交 `ff276290cc497650007e3a6106c2c5327f59539f` 的 allocator IT 1 项通过，覆盖租户隔离、并发、溢出和回滚。该证据来自独立运行，不是本地切片的数据库重跑。本切片没有连接 NAS；当前用户、组织、完整 finding.create HTTP/幂等流程与 Flowable 联合事务仍未做真实 PG18 验收。

## 五、后端对 issue #8 的回复（2026-10-31，针对 dev `cd1a8fd`）

| 问题 | 回复摘要 | 前端状态 |
|---|---|---|
| W1 `organizationId` | 由创建请求显式提供，服务端复核 `finding.create` 范围；**没有**“可创建隐患的组织”读取接口，具体契约未定稿，不要硬编码组织 UUID | 等后端出组织选项契约 |
| W2 `code` | 当前请求必须传；服务端没有编号生成器。建议改为服务端生成，规则、租户内唯一性、并发与幂等行为待定 | 等新契约，暂不改上报表单 |
| R4/W4 缺失字段与历史 | 位置、紧急程度、临时措施、来源分类、当前轮责任人、历史读取均**尚不支持**；不建议取消需求，也**不要把它们塞进 description，不要提交后静默丢弃** | 真实写入切换前须明确首期字段；未接线的项要标明“演示 / 不可用” |
| R3 时区 | 带偏移时间，服务端用 UTC；建议界面按 Asia/Shanghai，发送带 Z 或 +08:00 的 ISO | 已按此实现 |
| S1 会话 | 已有 `GET /api/v1/auth/csrf`、`POST /api/v1/auth/login`（`loginKey`、`password`、`tenantCode`）、`POST /api/v1/auth/logout`；缺认证 OpenAPI、当前用户 / 菜单 / 权限 / 组织选项接口；前端 `safety.finding.report` 与后端 `finding.create` 的映射待定；无完成日期承诺 | 合成 `SessionAdapter` 不能当真实登录 |
| 补充 A `grade` | 前端固定 `UNKNOWN` 可以保留，但服务端**目前不会拒绝** `GENERAL` / `MAJOR`，是待修的后端约束 | 前端不提供定级入口 |
| 补充 B 描述筛选 | 确认不支持；应标“隐患编号” | 已处理 |

单元测试 / CI 不代表真实登录、RLS、Flowable 联合事务或前后端联调已通过。当前批次未发表评论或更改 issue。

## 六、已商定的设计口径与实施顺序（2026-10-31，**设计口径，不表示代码已支持**）

实施顺序：不再把真实联调放到最后。先用真实数据库打通最小闭环“登录 → 当前用户与可用组织 → 创建 → 列表 → 详情”，再逐步加字段、历史和派单；每加一段做一次真实联调。契约由后端负责，前端从契约生成类型、使用模拟服务，CI 检查契约漂移。最小闭环暂不含附件，页面会明确说明。

**业务安全**

- 确实不成立：新增“不成立关闭”出口，必须填写理由，保留记录和审计；“退回补充”只用于信息不足。
- 上报人不得确认自己上报的隐患（含组织确认与安全确认），兼任角色不豁免；无人符合条件时转交其他有权限人员，不自动放行。
- “需立即处置”必须配通知（通知该组织配置的安全值班人员，记录送达、确认与失败，无人确认时升级）。通知闭环落地前，前端保留醒目的现场应急提示，**不显示“已通知”**。接收人、渠道、升级时限另行配置。

**接口契约（本地批次已定稿；尚未推送）**

- 创建响应返回 `findingId`、`code`、`state`、`roundNo`、`version`；`backfill` 暂不定义，补报阈值未经确认。
- 安全确认提交“等级 + 意见”；人员确认、不成立关闭及各种退回均携带意见，拒绝或退回理由必填。
- 来源枚举、紧急程度、24 小时补报和 7 天幂等保留期仍是提案，本轮未加入生产契约或实现。
- 紧急程度建议 `NORMAL`（普通）/ `URGENT`（需立即处置）：**两档**，与现有前端三档（含 `IMMEDIATE`）不同，契约定稿后前端改为后端枚举。紧急程度与一般 / 重大等级分开。
- 编号按服务端接收上报的北京时间日期生成，不按发现日期；列表按创建时间和稳定 ID 排序，不按编号字符串排序。
- 幂等：完整响应至少保留 7 天，之后保留标识到原记录的映射；过期重试不得静默新建，同标识不同内容返回冲突。
- 补报由后端计算（建议发现时间距提交超过 24 小时），阈值写入契约。
- 新建入口以“可上报组织列表非空”为条件；权限标识直接使用后端的 `finding.create` 等；403 后刷新用户、组织和菜单。

**前端待办（等契约接入后）**：接入组织选择（单个自动选中但仍展示，零个则不显示入口并说明）；创建结果展示服务端 `code`；补报展示要等阈值与持久字段定稿；新增“不成立关闭”等操作入口；会话、权限改用后端标识。

## 七、仍需处理的接入问题

1. R4/W4：位置、责任人、来源、紧急程度、临时措施、历史时间线仍需另外定稿并与写入/读取存储一起实现。
2. S2：菜单及权限接口、前端能力码到 `finding.create` 的映射仍需产品/接口批次确认。
3. 为最小闭环接入前端 HTTP adapter，并进行同源 CSRF/session 联调。
4. 使用现有 NAS PostgreSQL 18 实例完成授权角色下的当前用户、组织权限、创建编号并发/回滚、列表/详情及会话撤权验收；本轮未执行。
