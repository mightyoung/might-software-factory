# 前端与后端 OpenAPI 契约差异清单（隐患台账 / 上报）

状态：对照完成，**只对照了 `contracts/openapi/` 下三份契约文件和 `docs/finding-read-slice.md`，没有读后端实现代码，也没有联调**。前端仍然只有合成适配器，没有任何 HTTP 适配器。

对照对象：

- `contracts/openapi/finding-ledger.yaml`（只读：字段目录、分页列表、详情）
- `contracts/openapi/finding-workflow.yaml`（写入：创建隐患与流程命令）
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
| W1 | 请求必填 `organizationId`；前端没有这个值，也没有查询可选组织的接口 | 无法构造合法请求 | **需要后端说明组织从哪来**：会话默认组织，还是上报人选择？ |
| W2 | 请求必填 `code`（业务编号，1–64 字符），由请求方提供 | 前端不知道编号规则，也不应自己编 | **需要后端确认**：是客户端生成，还是应由服务端分配？ |
| W3 | 请求必填 `grade`；设计文档要求上报人不能定级 | 上报时只能固定传 `UNKNOWN` | 前端固定传 `UNKNOWN`，不给上报人选择；需后端确认服务端会拒绝其他值 |
| W4 | 请求没有 `location`、`source`、`urgency`、`interimMeasure` | 当前表单里这四个字段提交后会丢失 | 二选一：后端加字段；或前端把位置并入描述（会让描述变成拼接文本，不推荐） |
| W5 | `description` 长度 1–20000；前端 5–500 | 前端限制更严，属于产品假设 | 保留前端限制，不与契约冲突 |
| W6 | 响应只有 `findingId`、`state`、`roundNo`、`version`；没有 `submittedAt`、`backfill` | 成功页现在显示提交时间与补报标记 | 补报判定改在前端用 `discoveredAt` 与本地提交时间计算，或由后端返回 |
| W7 | 错误：400 请求无效、409 版本或幂等键冲突、422 流程规则不满足、503 依赖不可用 | 前端 `FindingSubmitError` 没有"流程规则"这一类 | 增加 `workflow-rule` 类并映射 422；400 对应 `validation` |

### 会话与菜单

| # | 差异 |
|---|---|
| S1 | `POST /api/v1/auth/login` 在 `finding-read-slice.md` 里有描述，但**没有 OpenAPI 文件**；没有"当前会话 / 当前用户"接口，也没有菜单与权限接口 |
| S2 | 因此前端 `SessionAdapter`（登录、菜单、`safety.finding.report` 权限）仍无法接真实后端 |

## 三、已有契约、前端尚未使用

`finding-workflow.yaml` 已有后续流程命令：确认（本人 / 安全部门）、派单、提交整改、验收，均要求 `expectedVersion`、`Idempotency-Key`、`X-XSRF-TOKEN`。前端目前这些按钮都是禁用的演示状态；接入时详情必须带 `version`（读取契约已提供）。

## 四、后端自己声明的阻塞（会影响联调时间）

摘自 `finding-ledger.yaml` 的 `x-runtime-blockers`：没有生产登录与会话签发配置；V3 / V4 迁移未应用到任何数据库；运行身份对身份与授权表没有 SELECT 权限；V2 只在测试库验证过。后端 handler 默认**拒绝访问**，所以当前即使写了 HTTP 适配器，也读不到真实数据。

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

后端同时提醒：单元测试 / CI 通过不代表真实登录、RLS、Flowable 联合事务或前后端联调已通过；issue #8 保持打开。

## 六、仍需回答的问题

1. W1：`organizationId` 从哪里来？
2. W2：隐患业务编号 `code` 由谁生成？
3. R4 / W4：位置、责任人、来源、紧急程度、临时措施、历史时间线，后端是否会提供？还是产品上取消这些字段？
4. R3：界面按哪个时区显示时间？
5. S1：会话 / 菜单 / 权限接口什么时候有契约？
