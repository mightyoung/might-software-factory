# 隐患复核确认输入与身份隔离契约

状态：PR #14 前向整合候选包含确认意见/等级、确认人隔离、“不成立”终态与关联新建契约，关闭迁移改为追加 V13，保留独立 V2 BPMN。此候选未应用 V13、未部署 V2；真实 PostgreSQL/Flowable 关闭验收仍待执行。当前证据及门禁见[前向候选就绪记录](verification/pr14-forward-candidate-readiness-2026-11-02.md)。

当前整合验证：已取得前向 V13 的独占本地 PostgreSQL/Flowable 证据、494 项 Linux 离线及 272 项前端测试；分批结果、跨迁移门禁与发布边界以 [当前验收报告](verification/pr14-forward-candidate-readiness-2026-11-02.md) 为准。下文未部署说明指 NAS/正式环境，历史草稿计数不作当前证据。
基线：当前候选将后端主线 `e028a959ab3d5dbe3314404fc72fe497dad01d82` 与原 PR #14 草稿 `39503493ff72356462b753b5e2a4134599281a82` 整合。已知源码安装链为 V2–V8、V11、V12；关闭迁移使用 `V13__finding_not_established_close_and_relation.sql`，不修改这些已知迁移。ledger schemaVersion 从 3 升至 4，以登记 `relatedFindingId` 与 `NOT_ESTABLISHED`。

原草稿使用 V10、基于 `dev` `d466eae636515f4f1f5935af9633e39d36213248`；原始文档与 SQL 保存在 [PR14 草稿源码](https://github.com/mightyoung/might-software-factory/tree/39503493ff72356462b753b5e2a4134599281a82)，其历史记录不被前向编号覆盖。历史 V9 已登记但缺少原始 Java 源码的目标仍为 **BLOCKED**；新独占已知源码安装链的结果不能证明历史目标可升级。V13 候选只针对已核验的已知源码链，不使用 `outOfOrder`、`repair`、`baseline`，不发明或重写 V9/V10 历史。

## 已批准的规则

- PERSON 和 SAFETY 阶段分别必须有有效确认人，且两阶段成员 ID 不同。
- 两位确认人都不能是当前上报人。
- 后端每次确认都从认证会话取得当前成员；不接受请求体的 actor/member 覆盖。
- 安全确认不能由已完成 PERSON 确认的同一成员提交，即使流程任务后来被错误地指派给该成员。
- 缺少指派、两阶段被指派给同一成员，或上报人被指派为任一确认人，均阻断创建/流程初始化并返回现有 422 `WORKFLOW_RULE`。待办不跳阶段，不创建空确认或启动错误的流程实例。
- 安全阶段要求找不到先前 PERSON 确认历史时拒绝继续；不以状态字段推定第一确认已经发生。
- 被拒绝的确认不变更状态/版本、不追加确认/转移/审计、不完成流程任务；确认的身份与历史检查先于幂等回执领取。创建/旧记录初始化中的无效确认路由在领取回执后检查，但异常会回滚事务中的回执和业务写入。
- 既有的租户/当前权限检查、版本比较和任务 assignee 校验继续生效。当前 Flowable 适配器会校验正在完成的 task actor 与 assignee 一致。
- PERSON 确认请求为 `{ expectedVersion, reason }`；SAFETY 确认请求为 `{ expectedVersion, reason, grade }`。两者 `reason` 保留 Java `String.strip()` 首尾规范化以兼容既有回执，并拒绝 Unicode White_Space 全空输入，原始 JSON 字符串长度最多 4000 个 Java UTF-16 字符单元；SAFETY 的 `grade` 只能是 `GENERAL` 或 `MAJOR`，PERSON 请求拒绝 `grade` 等未知字段。
- 确认意见写入确认记录、状态转移和审计；SAFETY 意见及等级也写入工作流审计与 finding 等级。意见/等级和阶段/版本进入确认命令指纹版本 `confirmation-payload-v2`；相同键重放同一完整请求返回既有结果，更改内容返回 409 `IDEMPOTENCY_CONFLICT`。
- 缺少必填意见、Unicode 空白全空意见、无效等级或缺少等级返回 400 `INVALID_REQUEST`；确认请求带未知字段返回 400 `INVALID_QUERY`。这类输入错误在事务及领取幂等回执前拒绝。

## “不成立”与退回补充的分离契约

1. PERSON 或 SAFETY 当前确认阶段可通过独立 `POST /api/v1/findings/{id}/close-not-established` 命令选择 `NOT_ESTABLISHED`；必须提交阶段、非空意见、版本和幂等键。该关闭请求不接受等级，关闭时保留 finding 原有等级；正常 SAFETY 批准仍必须提交并设置 `GENERAL`/`MAJOR`。
2. 命令以会话成员检查阶段权限、上报人排除、SAFETY 与 PERSON 确认人互斥，并要求绑定流程定义为 `findingRemediationV2`。V1 在途实例不自动迁移；V1 上调用关闭命令返回 409 `COMMAND_CONFLICT`，发生在领取回执和业务写入之前。正常 V1 批准路径仍可继续。
3. 关闭意见、确认决定、状态更新、transition、audit、流程任务完成和幂等回执在一个事务提交。相同键和相同规范化请求返回原结果；改阶段/版本/意见发生幂等冲突。`finding-not-established-v2` 指纹只包括阶段、版本和规范化意见，不包含等级。对流程引擎完成失败有事务回滚测试。
4. `NOT_ESTABLISHED` 不允许任何命令直接重开、回退或改写成待确认。V13 数据库触发器阻止从该状态更新到其他状态；现有整改验收通过的 `CLOSED` 不作“不成立”解释。
5. 收到新证据时通过创建请求的可选 `relatedFindingId` 建立一条同租户关联。服务端要求调用者保有子 finding 的 `finding.create` 权限及父记录的 `finding.read` 权限，并验证父记录可见且已是 `NOT_ESTABLISHED`；不存在/跨租户/不可读统一返回 404 `RESOURCE_NOT_VISIBLE`，可见但未终态返回 422 `WORKFLOW_RULE`。生成新的 ID、编号、描述/证据/确认链；旧记录不改。
6. 当前采用一条 child `finding.related_finding_id` 关联一条旧记录；V13 同租户复合外键、自关联 CHECK 和不可变 link trigger 提供数据库约束。若未来明确需要一条新报告关联多条历史记录，再独立设计关系表。
7. `RETURN_FOR_INFORMATION` 仍是未实现的非终态后续工作：需独立补充命令、追加补充历史和复核周期。它不复用 `NOT_ESTABLISHED`，补充也不能被渲染成关闭理由。

## 实现边界与版本兼容

V13 是追加迁移草案；它扩展状态检查、确认决定允许集、必填不成立理由和关联列/外键/触发器，不回写 V2–V8、V11、V12。它没有在本任务运行。V2 BPMN 文件为新流程定义新增明确的 PERSON 与 SAFETY 不成立结束分支；旧 V1 文件保持不变。应用代码的新建 finding 绑定 V2，关闭命令对 V1 进行版本检查并拒绝关闭。Flowable 8.0.0 的 binding 保留精确 `definition_id`，完成任务会校验当前实例定义与 binding 相同；仅新增/部署 BPMN 不会把已运行 V1 实例迁移。

发布顺序门槛：先在目标环境以批准流程部署 V2，再应用经过审查的 V13，最后发布会创建 V2 实例并读写关联字段的应用代码。此分支没有执行这些发布动作。V2/V13 未就绪时不得部署应用代码；缺少 V2 的环境中，新建流程会失败并回滚；不得回退到 V1 以绕开不成立终态。

兼容发布门槛：普通批准保持 Java `String.strip()` 规范化与已发布的 `confirmation-payload-v2`。既有合法请求（包括首尾 NBSP）的原正文、原键返回原结果；改变规范化后的正文仍返回 409 `IDEMPOTENCY_CONFLICT`。不改写旧回执、结果、意见或审计，不通过宽松回退伪造重放。缺少 `reason`、SAFETY `grade` 或发送 PERSON 未知字段仍按输入契约拒绝。Unicode White_Space 全空意见在领取回执前拒绝。

新“不成立”命令的意见首尾空白由 Java 校验器及 V13 数据库约束使用同一 Unicode White_Space 集合处理：U+0009–U+000D、U+0020、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000。仅由这些码点组成的意见被拒绝，规范化后意见写入确认、状态转移、审计和幂等指纹；原始输入仍按最多 4000 个 Java UTF-16 字符单元校验。

本地早期草案的 close 请求曾包含安全等级且指纹含等级；当前最终请求拒绝 `grade`，不以调用方等级覆盖原等级，关闭时保留 finding 原等级。`finding-not-established-v2` 指纹不包含等级。该关闭 API 仍未发布或部署；如果有环境曾手动运行这份本地草案，则需在发布前按不可变旧回执进行审计处置，不能假设没有回执。V13/V2 尚未应用/部署。

本次 PR14 前向候选整合没有连接 NAS、应用 V13 或部署 V2 BPMN；主线其他批次的实库证据不构成本关闭切片的 PostgreSQL/Flowable 验收。

## 当前测试覆盖与后续契约验收

- 原草稿本地测试范围（历史证据，迁移当时编号为 V10）：PERSON/SAFETY 关闭、非空意见与安全等级、确认人互斥/上报人排除、幂等重放/冲突、V1 关闭阻断、流程失败回滚、同租户终态关联、跨租户同 UUID 隐藏、无父记录读取/创建权限拒绝、V10 静态迁移契约、OpenAPI/BPMN XML 契约及前端状态类型。当前 V13 候选后端回归结果由后端 lane 单独记录，不沿用草稿计数；V13 目标数据库 migration 尚未执行。
- 当前前向候选前端：25 个文件、272 项测试及 TypeScript/生产构建通过；不成立终态显示且无流程动作，UI 未新增关闭命令入口。
- 仍待开发：`RETURN_FOR_INFORMATION`、上报人追加补充、复核周期、V1 活跃实例清点/迁移运营方案及 Flowable/PostgreSQL 真实集成验收。
