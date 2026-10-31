# 隐患复核确认输入与身份隔离契约

状态：本地整合切片实现确认意见/等级、确认人隔离、“不成立”终态与关联新建契约，新增 V10 迁移草案和 V2 BPMN。V10 未应用、V2 未部署；没有真实 PostgreSQL/Flowable 集成验收。
基线：以远端 `dev` `d466eae636515f4f1f5935af9633e39d36213248` 为起点，保留其中已整合的来源/紧急程度/位置/临时措施/补报字段及 schemaVersion 3、确认输入和错误映射。当前改动拟将 ledger schemaVersion 升至 4，以登记 `relatedFindingId` 与 `NOT_ESTABLISHED`。V8 保持原样；V9 已在测试库应用，但其源码不在这个 dev 树，本切片不复制、不重写 V9。V10 在进入完整发布树前必须确保 V9 的原始迁移源码由其 owner 纳入并通过 Flyway 历史校验。当前实现只新增 V10，不回写 V2–V9。

## 已批准的规则

- PERSON 和 SAFETY 阶段分别必须有有效确认人，且两阶段成员 ID 不同。
- 两位确认人都不能是当前上报人。
- 后端每次确认都从认证会话取得当前成员；不接受请求体的 actor/member 覆盖。
- 安全确认不能由已完成 PERSON 确认的同一成员提交，即使流程任务后来被错误地指派给该成员。
- 缺少指派、两阶段被指派给同一成员，或上报人被指派为任一确认人，均阻断创建/流程初始化并返回现有 422 `WORKFLOW_RULE`。待办不跳阶段，不创建空确认或启动错误的流程实例。
- 安全阶段要求找不到先前 PERSON 确认历史时拒绝继续；不以状态字段推定第一确认已经发生。
- 被拒绝的确认不变更状态/版本、不追加确认/转移/审计、不完成流程任务；确认的身份与历史检查先于幂等回执领取。创建/旧记录初始化中的无效确认路由在领取回执后检查，但异常会回滚事务中的回执和业务写入。
- 既有的租户/当前权限检查、版本比较和任务 assignee 校验继续生效。当前 Flowable 适配器会校验正在完成的 task actor 与 assignee 一致。
- PERSON 确认请求为 `{ expectedVersion, reason }`；SAFETY 确认请求为 `{ expectedVersion, reason, grade }`。两者 `reason` 按显式 Unicode White_Space 集合修剪首尾后必须非空，原始 JSON 字符串长度最多 4000 个 Java UTF-16 字符单元；SAFETY 的 `grade` 只能是 `GENERAL` 或 `MAJOR`，PERSON 请求拒绝 `grade` 等未知字段。
- 确认意见写入确认记录、状态转移和审计；SAFETY 意见及等级也写入工作流审计与 finding 等级。意见/等级和阶段/版本进入确认命令指纹版本 `confirmation-payload-v2`；相同键重放同一完整请求返回既有结果，更改内容返回 409 `IDEMPOTENCY_CONFLICT`。
- 缺少必填意见、Unicode 空白全空意见、无效等级或缺少等级返回 400 `INVALID_REQUEST`；确认请求带未知字段返回 400 `INVALID_QUERY`。这类输入错误在事务及领取幂等回执前拒绝。

## “不成立”与退回补充的分离契约

1. PERSON 或 SAFETY 当前确认阶段可通过独立 `POST /api/v1/findings/{id}/close-not-established` 命令选择 `NOT_ESTABLISHED`；必须提交阶段、非空意见、版本和幂等键。该关闭请求不接受等级，关闭时保留 finding 原有等级；正常 SAFETY 批准仍必须提交并设置 `GENERAL`/`MAJOR`。
2. 命令以会话成员检查阶段权限、上报人排除、SAFETY 与 PERSON 确认人互斥，并要求绑定流程定义为 `findingRemediationV2`。V1 在途实例不自动迁移；V1 上调用关闭命令返回 409 `COMMAND_CONFLICT`，发生在领取回执和业务写入之前。正常 V1 批准路径仍可继续。
3. 关闭意见、确认决定、状态更新、transition、audit、流程任务完成和幂等回执在一个事务提交。相同键和相同规范化请求返回原结果；改阶段/版本/意见发生幂等冲突。`finding-not-established-v2` 指纹只包括阶段、版本和规范化意见，不包含等级。对流程引擎完成失败有事务回滚测试。
4. `NOT_ESTABLISHED` 不允许任何命令直接重开、回退或改写成待确认。V10 数据库触发器阻止从该状态更新到其他状态；现有整改验收通过的 `CLOSED` 不作“不成立”解释。
5. 收到新证据时通过创建请求的可选 `relatedFindingId` 建立一条同租户关联。服务端要求调用者保有子 finding 的 `finding.create` 权限及父记录的 `finding.read` 权限，并验证父记录可见且已是 `NOT_ESTABLISHED`；不存在/跨租户/不可读统一返回 404 `RESOURCE_NOT_VISIBLE`，可见但未终态返回 422 `WORKFLOW_RULE`。生成新的 ID、编号、描述/证据/确认链；旧记录不改。
6. 当前采用一条 child `finding.related_finding_id` 关联一条旧记录；V10 同租户复合外键、自关联 CHECK 和不可变 link trigger 提供数据库约束。若未来明确需要一条新报告关联多条历史记录，再独立设计关系表。
7. `RETURN_FOR_INFORMATION` 仍是未实现的非终态后续工作：需独立补充命令、追加补充历史和复核周期。它不复用 `NOT_ESTABLISHED`，补充也不能被渲染成关闭理由。

## 实现边界与版本兼容

V10 是追加迁移草案；它扩展状态检查、确认决定允许集、必填不成立理由和关联列/外键/触发器，不回写 V2–V9。它没有在本任务运行。V2 BPMN 文件为新流程定义新增明确的 PERSON 与 SAFETY 不成立结束分支；旧 V1 文件保持不变。应用代码的新建 finding 绑定 V2，关闭命令对 V1 进行版本检查并拒绝关闭。Flowable 8.0.0 的 binding 保留精确 `definition_id`，完成任务会校验当前实例定义与 binding 相同；仅新增/部署 BPMN 不会把已运行 V1 实例迁移。

发布顺序门槛：先在目标环境以批准流程部署 V2，再应用经过审查的 V10，最后发布会创建 V2 实例并读写关联字段的应用代码。此分支没有执行这些发布动作。V2/V10 未就绪时不得部署应用代码；缺少 V2 的环境中，新建流程会失败并回滚；不得回退到 V1 以绕开不成立终态。

兼容发布门槛：旧确认请求若缺少 `reason` 或 SAFETY `grade` 会被拒绝（400）；PERSON 请求若发送等级等未知字段也会被拒绝（400）。旧回执保持不可变。本切片保留指纹标签 `confirmation-payload-v2`，但把意见规范化从 Java `String.strip()` 改为 Unicode White_Space 修剪；如果旧意见首尾含 NBSP 等码点，同一命令键和原始请求在新版本会因规范化后的指纹不同返回 409 `IDEMPOTENCY_CONFLICT`。本切片没有旧回执透明桥接，也不假设旧回执不存在。客户端收到此类冲突后先读取 finding 状态/版本，并通过有权限的审计途径确认意见、等级和命令是否已提交；读模型若不能确认准确意见，停止自动重试并由管理员核对。只有确认旧命令未成功、finding 仍处于原阶段且当前版本允许该操作后，才用新幂等键提交；不得用新正文覆盖旧键或改写历史回执。发布前须核实旧客户端和未过期回执。`confirmation-payload-v2` 是命令指纹组成部分，不代表整个 API schemaVersion 改版。

意见首尾空白由 Java 校验器及 V10 数据库约束使用同一 Unicode White_Space 集合处理：U+0009–U+000D、U+0020、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。仅由这些码点组成的意见被拒绝，规范化后意见写入确认、状态转移、审计和幂等指纹；原始输入仍按最多 4000 个 Java UTF-16 字符单元校验。

本地早期草案的 close 请求曾包含安全等级且指纹含等级；当前最终请求拒绝 `grade`，不以调用方等级覆盖原等级，关闭时保留 finding 原等级。`finding-not-established-v2` 指纹不包含等级。该关闭 API 仍未发布或部署；如果有环境曾手动运行这份本地草案，则需在发布前按不可变旧回执进行审计处置，不能假设没有回执。V10/V2 尚未应用/部署。

没有连接、改写或应用 NAS/数据库迁移，也没有部署 BPMN；不能据此声称真实 PostgreSQL/Flowable 流程验收通过。

## 当前测试覆盖与后续契约验收

- 本切片测试覆盖：PERSON/SAFETY 关闭、非空意见与安全等级、确认人互斥/上报人排除、幂等重放/冲突、V1 关闭阻断、流程失败回滚、同租户终态关联、跨租户同 UUID 隐藏、无父记录读取/创建权限拒绝、V10 静态迁移契约、OpenAPI/BPMN XML 契约及前端状态类型。目标数据库 migration 未执行，因此 V10 仅由静态契约测试审阅。
- 仍待开发：`RETURN_FOR_INFORMATION`、上报人追加补充、复核周期、V1 活跃实例清点/迁移运营方案及 Flowable/PostgreSQL 真实集成验收。
