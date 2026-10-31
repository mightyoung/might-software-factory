# 隐患确认安全规则：设计与测试规格

状态：已批准的业务规则已定稿；身份隔离和通用请求错误切片已在本地集成分支实现。当前整合分支后端 Surefire 175 项通过；LAN PG18 profile 的安全静态测试 12 项通过、58 个测试源编译通过；锁定生成器和漂移检查、前端 typecheck、Vitest 176 项通过。本规格描述的“不成立”、退回补充、意见/等级状态机、BPMN 与迁移仍未实现。
用途：记录确认身份隔离的设计、当前实现边界及后续验收规格。此次本地验证没有连接 PostgreSQL/NAS，也没有合并到 `dev`。

## 1. 核对范围与基线

核对的最初本地切片 HEAD 为 `4308ebddce4b2d410692c8080e1512afa336bfcc`，后续身份实现基于同一本地快照推进。该快照没有远端 Git 历史；GitHub 当前 `dev` `6b0c2ee55741f9dd610742dd0e277288534ac432` 已通过连接器只读核验，其源码树与本地快照基线 tree `8ce29a10f7c2d28babf6c6305b08bbfd29f54fc5` 一致。当前工作按已验证的 GitHub API tree/commit 创建 PR #9，不把本地快照提交 SHA 误称为远端原始历史。仓库没有找到 `AGENTS.md`、`CONTEXT.md` 或 `CONTEXT-MAP.md`。

本规格应用两个术语：**上报人**是当前人工创建端点会话中的成员；**复核周期**是补充材料后的重新确认轮次，与整改的 `round_no` 分开。当前创建服务以会话成员写入 `created_by_member_id`，故在当前接口范围内用该字段识别上报人；若未来有系统代报或代理上报入口，应另设明确的 `reporter_member_id`，不能把创建账号推定为报告责任人。

### 原始代码基线证据（身份隔离补丁实施前）

- 业务基线要求先人员确认、再安全确认；不能省第一确认；误报/重复需有理由留痕。见 [business-requirements-and-scenarios.md:62](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/docs/business-requirements-and-scenarios.md:62)。
- V2 对 `finding.state` 的检查仅列出 `DISCOVERED`、两级待确认、整改状态和 `CLOSED`，没有退回补充或不成立状态。见 [V2__dualprevention_finding_read.sql:25](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/resources/db/migration/V2__dualprevention_finding_read.sql:25)。
- V5 的 `finding_confirmation` 只允许 `APPROVE`，`reason` 可空，主键限定每个隐患每阶段仅一条。见 [V5__finding_workflow_commands.sql:69](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/resources/db/migration/V5__finding_workflow_commands.sql:69)。
- `confirmPerson` / `confirmSafety` 当前只传阶段和版本；共享命令检查权限、版本、推进状态并写确认，未检查执行成员是否为上报人，确认没有意见字段。见 [FindingWorkflowCommandService.java:145](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/FindingWorkflowCommandService.java:145) 和 [FindingWorkflowCommandService.java:283](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/FindingWorkflowCommandService.java:283)。
- 当前创建强制等级为 `UNKNOWN`，调用人由服务端会话提供；安全确认没有设置等级的参数。见 [FindingWorkflowApiModels.java:27](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/FindingWorkflowApiModels.java:27)。
- 现有服务已有有用的安全基线：每次命令重新检查当前权限和租户成员状态；整改人不能验收自己的提交；按行锁、版本比较、命令回执和请求哈希实现并发冲突与幂等。见 [PersistentFindingWorkflowAccessPolicy.java:11](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/PersistentFindingWorkflowAccessPolicy.java:11)、[FindingWorkflowCommandService.java:249](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/FindingWorkflowCommandService.java:249)、[JooqFindingWorkflowRepository.java:68](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/JooqFindingWorkflowRepository.java:68)。
- 现有服务测试覆盖完整确认链、命令幂等和整改人不能自验收，但没有上报人不能自确认的用例。见 [FindingWorkflowCommandServiceTest.java:83](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/test/java/com/mightyoung/softwarefactory/dualprevention/FindingWorkflowCommandServiceTest.java:83) 和 [FindingWorkflowCommandServiceTest.java:324](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/test/java/com/mightyoung/softwarefactory/dualprevention/FindingWorkflowCommandServiceTest.java:324)。
- `finding_transition` 与 `audit_event` 已在同一命令事务写入；两者可携带原因、执行人和版本变化。现在确认命令传入的原因是空值。见 [JooqFindingWorkflowRepository.java:193](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/workflow/JooqFindingWorkflowRepository.java:193)。
- 当前 BPMN 只连通人员确认→安全确认→派单→整改→验收；没有“补充资料”或“不成立”分支。见 [finding-remediation-v1.bpmn20.xml:6](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/backend/src/main/resources/processes/finding-remediation-v1.bpmn20.xml:6)。
- 基线后端固定使用 Flowable 8.0.0。`workflow_binding` 记录精确 `definition_id`，完成任务时会验证流程实例的定义 ID 与绑定一致；只部署新版 BPMN 不会自动迁移在途 V1 实例。本轮仅实现命令服务的成员隔离，没有新增 BPMN/状态/迁移。
- 已实现切片从 `finding_confirmation` 读取同租户、同隐患的 PERSON 确认成员；Safety 命令在当前会话成员与上报人、历史 PERSON 确认成员逐次比较。路由创建及旧 `DISCOVERED` 初始化前检查两个确认人不相同且都不同于上报人。
- 本地前端状态枚举同样只有七态，但动作草案已经列出“退回补充”“退回第一确认”，未找到对应后端命令。见 [finding.ts:1](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/frontend/src/domain/finding.ts:1)。

## 2. 已批准规则与后续推荐

1. **每个确认阶段都是独立命令。** 人员确认不可省略；角色兼任、重复角色、相同人员配置或历史迁移都不能自动通过或跳过任一阶段。
2. **两位确认人必须互不相同，且都不能是上报人。** 用户已批准：PERSON/SAFETY 路由的成员 ID 必须不同；两人都不得等于当前上报人；每次确认也必须从服务端会话身份逐次验证，不能从请求体接收 actor。缺少合格确认人时返回配置/业务阻断，不自动跳过。
3. **确认意见与等级契约。** 按现有业务要求，PERSON 确认和退回意见必填；SAFETY 确认需显式等级及意见。完整 decision/reason/grade 契约作为后续 API/工作流批次实现，本轮隔离补丁未触碰并行线程正在生成的 OpenAPI/TS 文件。
4. **不成立不可直接重开。** 用户已批准：`NOT_ESTABLISHED` 与整改验收通过的 `CLOSED` 区分；新证据应创建新的 finding 并关联原不成立记录，原记录不变。推荐非空关闭理由及追加审计。初版不从旧 `CLOSED` 推断为不成立。
5. **退回补充与不成立分离。** `RETURN_FOR_INFORMATION` 是非终态，转到 `REPORTER_SUPPLEMENT_PENDING`；上报人追加补充说明，不覆盖初次描述。推荐补充后回到 `PERSON_CONFIRM_PENDING` 并递增独立 `review_cycle`，让两级重审新材料；旧决定保留。
6. **没有有效确认人时保持阻塞。** 缺人、成员停用、指派失效或指派重叠均不得自动跳过阶段。给出可操作的路由配置错误，待有效指派后再处理。
7. **阶段动作与 DB 状态、命令回执、决定记录、状态历史、审计应同一事务提交。** 任一流程引擎动作失败则整笔回滚；继续沿用 `expectedVersion`、命令幂等键和乐观锁。确认命令的身份/历史拒绝在领取命令回执前发生；创建/旧记录初始化的路由拒绝在领取后发生，但异常回滚回执和业务写入。

### 状态变化

| 当前状态 | 命令 | 后续状态 | 额外要求 |
|---|---|---|---|
| `PERSON_CONFIRM_PENDING` | 人员 `APPROVE` | `SAFETY_CONFIRM_PENDING` | 意见必填；执行人≠上报人 |
| `PERSON_CONFIRM_PENDING` | 人员 `RETURN_FOR_INFORMATION` | `REPORTER_SUPPLEMENT_PENDING` | 退回意见必填 |
| `PERSON_CONFIRM_PENDING` | 人员 `NOT_ESTABLISHED` | `NOT_ESTABLISHED` | 关闭意见必填 |
| `SAFETY_CONFIRM_PENDING` | 安全 `APPROVE` | `SAFETY_DISPATCH_PENDING` | `GENERAL` 或 `MAJOR` + 意见必填 |
| `SAFETY_CONFIRM_PENDING` | 安全 `RETURN_FOR_INFORMATION` | `REPORTER_SUPPLEMENT_PENDING` | 等级字段必填（资料不足可为 UNKNOWN）+ 退回意见必填 |
| `SAFETY_CONFIRM_PENDING` | 安全 `NOT_ESTABLISHED` | `NOT_ESTABLISHED` | 等级字段 + 关闭意见必填；等级不代表隐患成立 |
| `REPORTER_SUPPLEMENT_PENDING` | 上报人追加补充 | `PERSON_CONFIRM_PENDING` | 补充内容必填；`review_cycle + 1` |
| `ACCEPTANCE_PENDING` | 验收通过 | `CLOSED` | 既有整改验收规则保持 |

确认命令只在对应待处理状态有效。任何其他状态下的决定均无副作用并返回状态冲突/规则错误。整改 `round_no` 不因复核退回而改变。

## 3. 命令与前后端契约建议

保留现有路由以减少接口搬迁，但把两个确认请求升级为同一明确决策契约；旧客户端缺少意见时明确失败，不能默认为通过。

- `POST /api/v1/findings/{id}/confirm/person`
- `POST /api/v1/findings/{id}/confirm/safety`
- 新增 `POST /api/v1/findings/{id}/supplements`

确认请求建议为 `{ expectedVersion, decision, reason, grade? }`。只允许 `APPROVE`、`RETURN_FOR_INFORMATION`、`NOT_ESTABLISHED`。所有 SAFETY 决定都要求显式 `grade`；安全 `APPROVE` 必须是 `GENERAL` 或 `MAJOR`，退回可为 `UNKNOWN`，未成立时该字段只记录评估意见、不证明隐患成立。PERSON 决定不接受等级。补充请求为 `{ expectedVersion, statement }`，只接收当前上报人会话，不接收 actor/member 字段。响应增加 `reviewCycle`，状态枚举增加上述两态；`roundNo` 继续只表示整改轮次。

请求哈希要包含阶段、决定、去空白后的意见、等级、版本（补充命令还含补充文本）。相同键与相同请求返回原结果；相同键改了意见或等级返回冲突。权限和阶段指派检查必须先于回执重放，撤权成员不能通过重放读取先前成功结果。

### 后端逐次校验顺序

在租户事务中锁定 finding 当前行后：

1. 从已验证会话取 `actorId/memberId/tenantId`；拒绝请求体中的身份覆盖。
2. 检查会话、人类主体、账号/租户/成员有效状态、权限纪元和组织范围。
3. 读取当前有效的阶段指派并确认 actor 是该阶段指派成员；同时核验成员仍有效、仍有阶段权限。撤权、离岗或指派变更后的旧任务不能仅凭 BPMN 中过期的 assignee 继续执行。
4. 对两级确认比较 `actor.memberId != finding.created_by_member_id`。拒绝在领取命令回执或变更任何业务数据前发生；并发撤权/指派变更须与这些读取串行化（锁相应授权/指派行，或用等效的事务冲突机制）。
5. 校验当前状态、`expectedVersion`、理由和阶段决策数据；以含完整请求字段的指纹领取幂等键。
6. 在同一事务写决定/补充、更新 finding、追加 transition 与成功审计、推进对应流程任务，完成回执并发布变更。

第 3 步要求把“有效的任务参与者”从仅依赖创建时 BPMN 变量推进到每次命令检查。缺少当前指派时返回阻塞错误，不自动分配给上报人或其他有多角色权限者。

## 4. 新迁移与审计

本地切片迁移编号到 V7；建议仅追加 `V8__finding_review_safety_controls.sql`，不改写 V2–V6，也不改 V7：

- 扩展 `finding.state` 检查，允许 `REPORTER_SUPPLEMENT_PENDING`、`NOT_ESTABLISHED`，保留旧值 `DISCOVERED` 和 `CLOSED`。为 `finding` 新增正数 `review_cycle`，历史行默认 1；不得用 `round_no` 兼作复核周期。
- 新建按租户隔离的 `finding_review_decision`，记录 finding、review_cycle、stage、actor member、decision、reason、可选 confirmed_grade、expected_version、command_id、时间；同一周期同一阶段最多一项，并按命令 ID 唯一。决定表仅追加。
- 新建按租户隔离的 `finding_report_supplement`，记录被补充的周期、上报人、补充正文、命令 ID、时间；追加写入，不能覆写初次描述或早期补充。
- `CHECK`：阶段只允许 PERSON/SAFETY；决定只允许上述三种；`reason` 去空白后非空；SAFETY 每项都必须有 UNKNOWN/GENERAL/MAJOR 等级，安全 `APPROVE` 只能为 GENERAL/MAJOR；PERSON 决定不得伪装写入等级；周期与版本为正数。若要在 DB 层进一步拒绝跨表的“上报人自审”或状态绕过，使用约束触发器（普通 `CHECK` 不能引用另一行），同时保留服务层逐次检查。
- 对两张新历史表加拒绝 UPDATE/DELETE 的触发器；确认流程转移时 `finding_transition.reason` 与 `audit_event.reason` 写同一清理后的意见，审计扩展记录 stage/decision/grade/reviewCycle。已有 `audit_event` 已是只追加表。所有审计插入仍要求和状态一起提交。
- 新代码开始写入 `finding_review_decision`，旧 `finding_confirmation` 保留为 V5 历史，只读兼容，不向其单行每阶段主键继续塞返回/重审记录。审计读取层合并展示新旧两表，不能伪造旧决定的意见、等级或身份。
- 新建的 `finding` 状态在安全批准时和决定行同事务更新 grade。DB 检查/触发器可约束“安全待确认→派单”必须有当前周期含等级的安全批准，避免绕过服务层；不要加会使旧的未知等级整改/验收记录无法更新的全表 CHECK。

迁移前需只读统计旧状态和 `grade='UNKNOWN'` 的数量。当前代码创建时强制 UNKNOWN，旧安全确认又不设置 grade，因此不能推断已运行到整改/验收的记录等级。不得自动填 GENERAL/MAJOR；保留旧行原值并报告数量。新规则须对升级后仍待安全确认的命令生效。

## 5. BPMN 与旧流程兼容

新增独立 BPMN 定义（例如 `finding-remediation-v2.bpmn20.xml`），不要在旧流程定义上原地改语义。人员和安全任务之后增加各自决策网关：

- `APPROVE` 沿现有双确认链继续；
- `RETURN_FOR_INFORMATION` 到指定上报人补充任务，完成后回人员确认；
- `NOT_ESTABLISHED` 到独立结束事件，不进入派单/整改，不伪装成验收通过。

流程引擎只执行命令服务已验证的决定；不能让流程变量自行绕过 DB 状态。新建记录和显式初始化的旧 `DISCOVERED` 记录使用新定义。

对已运行的 V1 流程不可静默切换实例。启用退回/不成立功能前，必须以当前 Flowable 版本验证旧实例迁移到新定义的完整路径；不能迁移的记录要么继续仅允许受新身份/意见/等级规则保护的批准路径，要么在开放新决策前完成经验证的停滞实例迁移方案。若两者均不可行，阻止该批次上线，不允许“DB 已变更、流程任务没变”或旧流程漏掉新限制。

## 6. 测试先行的小批次

以下是实现规格，不代表本任务已运行测试。每批不超过一天；先补失败测试，再实现，再跑该批测试。

| 批次 | 估时 | 先写的测试与通过条件 |
|---|---:|---|
| A. 确认人隔离与每次授权复核 | 0.5 天 | **A1 已实现并通过**：测试上报人分别调用 PERSON/SAFETY 被拒；PERSON 与 SAFETY 指派重复、任一确认人等于上报人时创建/旧 `DISCOVERED` 初始化均被阻断且不产生业务副作用；SAFETY 缺少 PERSON 历史被阻断；故意把流程任务改派给上报人或同一 PERSON 确认人，命令服务仍拒绝。先运行旧实现得到 5 个新增拒绝测试失败，再实现，定向类 23/23 通过，全 `backend` 测试 169/169 通过。**后续 A2 未实现**：撤权/改派并发与回执重放、任务 assignee 变化时的动态授权、完整状态/transition/audit 不变及 HTTP actor 覆盖测试仍需补齐。 |
| B. 意见、等级与不成立终态 | 0.75 天 | 状态机测试：每个阶段三种决定只走允许转换；空/空白意见失败；任一 SAFETY 决定缺等级失败；安全 APPROVE 用 UNKNOWN 失败，GENERAL/MAJOR 成功并写入 finding；`NOT_ESTABLISHED` 与验收 `CLOSED` 可区分，旧 CLOSED 不改；理由/演员/决定/版本一致写入 transition、审计和追加决策。迁移契约测试覆盖 state/outcome/reason/grade CHECK 与历史表不可修改。 |
| C. 退回与补充周期 | 1 天 | PERSON 与 SAFETY 的退回都需意见并转等待补充；只有原上报人能提交非空补充，其他人及其他状态均拒绝；补充只追加不覆盖初始描述；周期递增、回 PERSON 再走完整人员和安全确认；旧决定可按周期查询。用并发/事务测试验证竞争版本只有一个提交，流程失败时补充、状态、审计和回执一起回滚。 |
| D. 幂等/撤权并发与 BPMN | 1 天 | 同键同完整请求重放同结果；同键更改意见/等级/补充文本得到 409；过期版本得到 409；新 BPMN 三条决定路由与数据库状态逐一匹配；旧流程实例不被部署新版本静默改写；无法迁移时新决定被阻断；撤权与命令并发有确定的串行化结果。用流程引擎集成测试覆盖新旧定义，不以 XML 字符串测试代替行为。 |
| E. OpenAPI / 前端状态与错误呈现 | 0.5 天 | 契约测试锁定新 decision、reason、safety grade、supplement 和两个新增 state；生成 TS 类型后增加 exhaustiveness 测试；UI 只在当前状态展示可用动作，详情时间线区分退回意见、补充、未成立理由与验收关闭。测试 400/401/403/404/409/422/503 的呈现，不得把网络失败或排队请求显示成完成。 |

### 前端状态和错误建议

| 后端结果 | 前端处理 |
|---|---|
| `NOT_ESTABLISHED` | 显示“未成立”及理由，和“整改验收通过/已闭环”分开；不提供正常整改动作。 |
| `REPORTER_SUPPLEMENT_PENDING` | 仅上报人显示退回意见和补充输入；确认人看到待补充及只读材料。补充后按新周期显示两阶段待确认。 |
| `CLOSED` | 文案明确为验收通过；不能作为未成立的映射。 |
| 400 `INVALID_REQUEST` | 展示 reason/grade/statement 字段错误。 |
| 401 `AUTH_REQUIRED`、403 `ACTION_DENIED` | 重新登录或说明当前账号无该阶段处理资格；不反复自动提交。 |
| 404 `RESOURCE_NOT_VISIBLE` | 通用不可见提示，不探测是否跨租户存在。 |
| 409 `VERSION_CONFLICT` / `IDEMPOTENCY_CONFLICT` / `COMMAND_CONFLICT` | 刷新详情与版本后由用户重新确认；同键不同正文提示命令键冲突，不换键自动重放。后端当前已分别返回版本冲突、幂等冲突与工作流任务状态冲突。 |
| 缺失/失效阶段人 | 建议新增 409 `REVIEW_ROUTE_UNAVAILABLE`，提示管理员修复当前阶段指派；当前代码会把流程配置错误映射成 422 `WORKFLOW_RULE`，若暂不扩展错误结构则保留通用码并给出稳定子码。 |
| 422 `WORKFLOW_RULE` | 明确提示当前状态不允许该决定或规则字段缺失；不清除用户意见。 |
| 503 `DEPENDENCY_UNAVAILABLE` | 保留输入并提示用相同 Idempotency-Key 查询/重试；结果未知时不生成新键。 |

原基线异常处理器将 DTO 校验失败映射为 400 `INVALID_REQUEST`，但不填充字段错误，并把版本/幂等/任务冲突都归为 409 `COMMAND_CONFLICT`。当前错误切片已返回稳定字段路径和机器可读校验码；版本过期返回 `VERSION_CONFLICT`，幂等键冲突返回 `IDEMPOTENCY_CONFLICT`，工作流任务状态冲突保留 `COMMAND_CONFLICT`。Malformed JSON/绑定错误与不支持的创建字段返回 `INVALID_QUERY`，生命周期命令 DTO 对未知字段的当前兼容行为也已在 OpenAPI 明确说明。前端 HTTP adapter 尚未接入这些错误响应。

紧急通知只在实际未接收/未送达时显示“待接收/未确认”，不能由派单或创建通知记录推断“已通知”。本规格不新增通知传输或送达实现。

## 7. 已定稿决策与剩余风险

1. **已批准且实现：** PERSON 与 SAFETY 确认人必须是不同成员，且均不能是上报人；无合法人选时阻断。测试与代码位于本交付补丁。
2. **已批准但仅定稿、未实现：** `NOT_ESTABLISHED` 为终态；新证据创建独立 finding 并关联旧记录，不在原记录重开。关联方向、唯一/多关联 DB 形式需在 API/迁移实现前定型；本稿建议创建请求接收单个可选 `relatedFindingId`，多关联需求则采用关系表。
3. **V1 在途流程迁移** 是发布门槛。Flowable 8.0.0 下当前绑定精确流程定义 ID；新增 V2 BPMN 不会迁移活跃实例。需要集成验证迁移或明确阻断/旧实例处置策略。本轮不更改流程定义，故没有因运行实例兼容性未知而假设可安全启用新分支。
4. **兼容的未知等级记录** 不能靠迁移猜测。应只读统计并保持旧值；若业务要求旧整改件补等级，另作有审计的分类批次。
5. `finding_confirmation` 现有唯一键不能表达多轮意见；V5 不应被改写。新增决定/补充表应追加迁移，读取层兼容旧表。

## 8. 非目标

身份隔离实现批次本身不处理账号模型、编号实现、通知实现、附件/NAS、密钥、部署、意见/等级命令、状态机、迁移或 BPMN。当前集成分支另包含错误响应映射及 OpenAPI/TypeScript 类型切片。审批/审核失败不得写成已成功；任何流程外紧急处置仍不可因内部审批等待而被界面描述为已通知或已办结。
