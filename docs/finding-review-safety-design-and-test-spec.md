# 隐患确认安全规则：设计与测试规格

状态：确认人隔离、必填确认意见/等级、`NOT_ESTABLISHED` 终态、关联新建、V10 迁移草案、V2 BPMN 文件和前端状态契约已在独立本地整合切片实现。后端 226 项测试、前端 177 项测试、类型检查和 API 类型生成校验通过。退回补充/复核周期未实现；V10 未应用、V2 BPMN 未部署；没有真实 PostgreSQL/Flowable 集成验收。本文较早的测试计数属于历史证据，不是当前整合树结果。
用途：记录确认安全规则、独立关闭切片的实现边界及仍需验收的部分。此分支不执行数据库迁移或部署；单测/XML/静态迁移测试不等同真实 PostgreSQL/Flowable 流程验收。具体 API 与兼容条件见 [finding-review-safety-contract.md](finding-review-safety-contract.md)。

当前整合树以远端 `dev` `d466eae636515f4f1f5935af9633e39d36213248` 为基线，包含已整合的报告元数据、确认输入和读写契约。先前的身份隔离工作树 `7ba22f369219ef8680284ed1bb8165e57caa4f58` 及其初始远端基线仅是历史分析快照。V8 已包含在当前基线；V9 已在测试库应用，但对应源码不在此 dev 树，本切片不复制或改写它；关闭切片使用 V10 草案。整合新增关系字段和 `NOT_ESTABLISHED` 状态后，ledger `schemaVersion` 从 3 升至 4。V10 未应用，V2 BPMN 未部署。

## 1. 核对范围与基线

核对的最初本地切片 HEAD 为 `4308ebddce4b2d410692c8080e1512afa336bfcc`，后续身份实现基于同一本地快照推进。该快照没有远端 Git 历史；PR #9 已将该工作整合进远端 `dev`。当前确认输入切片以远端 `dev` `61c83651b1a35340ef814524095686ae784d0d37` 的树为基线；本地工作树是经只读核验重建的快照，不把本地提交 SHA 误称为远端原始历史。仓库没有找到 `AGENTS.md`、`CONTEXT.md` 或 `CONTEXT-MAP.md`。

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
- 基线后端固定使用 Flowable 8.0.0。`workflow_binding` 记录精确 `definition_id`，完成任务时会验证流程实例的定义 ID 与绑定一致；只部署新版 BPMN 不会自动迁移在途 V1 实例。早期确认人隔离切片没有新增 BPMN/状态/迁移；后续关闭切片新增独立 V2 BPMN 与 V10 草案，详见本文第 4–5 节。
- 已实现切片从 `finding_confirmation` 读取同租户、同隐患的 PERSON 确认成员；Safety 命令在当前会话成员与上报人、历史 PERSON 确认成员逐次比较。路由创建及旧 `DISCOVERED` 初始化前检查两个确认人不相同且都不同于上报人。
- 本地前端状态枚举同样只有七态，但动作草案已经列出“退回补充”“退回第一确认”，未找到对应后端命令。见 [finding.ts:1](/Users/muyi/Documents/Codex/2026-10-30/task-10/api-contract-dev-6b0c2ee/frontend/src/domain/finding.ts:1)。

## 2. 已批准规则与后续推荐

1. **每个确认阶段都是独立命令。** 人员确认不可省略；角色兼任、重复角色、相同人员配置或历史迁移都不能自动通过或跳过任一阶段。
2. **两位确认人必须互不相同，且都不能是上报人。** 用户已批准：PERSON/SAFETY 路由的成员 ID 必须不同；两人都不得等于当前上报人；每次确认也必须从服务端会话身份逐次验证，不能从请求体接收 actor。缺少合格确认人时返回配置/业务阻断，不自动跳过。
3. **确认意见与等级契约（本地实现）。** PERSON 请求 `{ expectedVersion, reason }`，SAFETY 正常批准请求 `{ expectedVersion, reason, grade }`；意见首尾按 Unicode White_Space 集合修剪且修剪后必须非空，原始字符串最多 4000 个 Java UTF-16 字符单元。SAFETY 批准等级只接受 `GENERAL`/`MAJOR`，PERSON 请求拒绝等级和任意未知字段。批准命令指纹采用 `confirmation-payload-v2`，将阶段、版本、意见与（安全批准）等级纳入同键重放判定；规范化意见写入确认、状态转移与审计，安全批准等级同步写入 finding。`NOT_ESTABLISHED` 关闭请求不接收 grade，关闭时保留原等级。
4. **不成立不可直接重开（已实现）。** `NOT_ESTABLISHED` 与整改验收通过的 `CLOSED` 区分；新证据创建新的 finding 并通过可选 `relatedFindingId` 关联原记录，原记录不变。关闭理由非空并进入确认、状态转移和审计。初版不从旧 `CLOSED` 推断为不成立。
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
| `SAFETY_CONFIRM_PENDING` | 安全 `NOT_ESTABLISHED` | `NOT_ESTABLISHED` | 关闭意见必填；不提交/设置等级，原等级保持不变 |
| `REPORTER_SUPPLEMENT_PENDING` | 上报人追加补充 | `PERSON_CONFIRM_PENDING` | 补充内容必填；`review_cycle + 1` |
| `ACCEPTANCE_PENDING` | 验收通过 | `CLOSED` | 既有整改验收规则保持 |

确认命令只在对应待处理状态有效。任何其他状态下的决定均无副作用并返回状态冲突/规则错误。整改 `round_no` 不因复核退回而改变。

## 3. 命令与前后端契约建议

当前确认输入切片使用两个阶段专属批准路由 `POST /confirm/person` 和 `POST /confirm/safety`；不成立终态使用独立 `POST /close-not-established`，请求是 `{ expectedVersion, stage, reason }`。关闭意见必填；关闭命令拒绝 grade 并保留 finding 已有等级，普通 SAFETY 批准仍必须显式带等级。服务没有将多个决定合并进 `decision` 字段，也没有补充接口。下列 `RETURN_FOR_INFORMATION` /补充命令草案属于后续批次，不应视为已实现 API。

保留现有路由以减少接口搬迁，但把两个确认请求升级为同一明确决策契约；旧客户端缺少意见时明确失败，不能默认为通过。

- `POST /api/v1/findings/{id}/confirm/person`
- `POST /api/v1/findings/{id}/confirm/safety`
- 后续补充批次才新增 `POST /api/v1/findings/{id}/supplements`

未来若统一决定类型，确认请求可演进为 `{ expectedVersion, decision, reason, grade? }`，只允许 `APPROVE`、`RETURN_FOR_INFORMATION`、`NOT_ESTABLISHED`。安全 `APPROVE` 必须为 `GENERAL`/`MAJOR`；安全退回是否需要 UNKNOWN 等级待产品决策；`NOT_ESTABLISHED` 不应设置等级且必须保留原等级。PERSON 决定不接受等级。补充请求为 `{ expectedVersion, statement }`，只接收当前上报人会话，不接收 actor/member 字段。若引入多轮复核，再增加 `reviewCycle`；`roundNo` 继续只表示整改轮次。上述 API 收敛属于未来兼容设计，不纳入本切片。

请求哈希包含阶段、版本、规范化意见；普通 SAFETY 批准还包含等级。关闭命令不含等级字段。相同键与相同规范化请求返回原结果；相同键改了意见或（批准请求中的）等级返回冲突。权限和阶段指派检查必须先于回执重放，撤权成员不能通过重放读取先前成功结果。

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

当前关闭切片新增 `V10__finding_not_established_close_and_relation.sql` 草案，不修改 V2–V9。V8 已包含在整合基线；V9 已在测试库应用但源码不在此 dev 树，V10 不能在缺少经 owner 纳入并校验的 V9 原始迁移时申请执行。V10 目前包括：

- 将 `NOT_ESTABLISHED` 加入 `finding_state_valid`，将 `finding_confirmation.decision` 扩为 `APPROVE`/`NOT_ESTABLISHED`，并要求不成立决定有非空 reason。
- 不成立意见 CHECK 与应用层共享 Unicode White_Space 字符集合；NBSP、ASCII 空格和制表符等全空值均拒绝。
- 新增 nullable `related_finding_id`、同租户复合外键、自关联检查、仅关联 `NOT_ESTABLISHED` 父记录的触发器，以及关系不可变约束。
- 禁止 `NOT_ESTABLISHED` finding 被 UPDATE 到其它状态；保留旧 `CLOSED` 语义。

V10 未应用于 NAS 或任何数据库。多轮退回所需周期/追加补充表仍需单独设计和迁移：

- 扩展 `finding.state` 检查，允许 `REPORTER_SUPPLEMENT_PENDING`、`NOT_ESTABLISHED`，保留旧值 `DISCOVERED` 和 `CLOSED`。为 `finding` 新增正数 `review_cycle`，历史行默认 1；不得用 `round_no` 兼作复核周期。
- 新建按租户隔离的 `finding_review_decision`，记录 finding、review_cycle、stage、actor member、decision、reason、可选 confirmed_grade、expected_version、command_id、时间；同一周期同一阶段最多一项，并按命令 ID 唯一。决定表仅追加。
- 新建按租户隔离的 `finding_report_supplement`，记录被补充的周期、上报人、补充正文、命令 ID、时间；追加写入，不能覆写初次描述或早期补充。
- `CHECK`：阶段只允许 PERSON/SAFETY；决定只允许上述三种；`reason` 去空白后非空；SAFETY 每项都必须有 UNKNOWN/GENERAL/MAJOR 等级，安全 `APPROVE` 只能为 GENERAL/MAJOR；PERSON 决定不得伪装写入等级；周期与版本为正数。若要在 DB 层进一步拒绝跨表的“上报人自审”或状态绕过，使用约束触发器（普通 `CHECK` 不能引用另一行），同时保留服务层逐次检查。
- 对两张新历史表加拒绝 UPDATE/DELETE 的触发器；确认流程转移时 `finding_transition.reason` 与 `audit_event.reason` 写同一清理后的意见，审计扩展记录 stage/decision/grade/reviewCycle。已有 `audit_event` 已是只追加表。所有审计插入仍要求和状态一起提交。
- 新代码开始写入 `finding_review_decision`，旧 `finding_confirmation` 保留为 V5 历史，只读兼容，不向其单行每阶段主键继续塞返回/重审记录。审计读取层合并展示新旧两表，不能伪造旧决定的意见、等级或身份。
- 新建的 `finding` 状态在安全批准时和决定行同事务更新 grade。DB 检查/触发器可约束“安全待确认→派单”必须有当前周期含等级的安全批准，避免绕过服务层；不要加会使旧的未知等级整改/验收记录无法更新的全表 CHECK。

迁移前需只读统计旧状态和 `grade='UNKNOWN'` 的数量。当前代码创建时强制 UNKNOWN，旧安全确认又不设置 grade，因此不能推断已运行到整改/验收的记录等级。不得自动填 GENERAL/MAJOR；保留旧行原值并报告数量。新规则须对升级后仍待安全确认的命令生效。

## 5. BPMN 与旧流程兼容

新增独立 BPMN 定义 `finding-remediation-v2.bpmn20.xml`；旧 `finding-remediation-v1.bpmn20.xml` 未修改。人员和安全任务之后增加各自决策网关：

- `APPROVE` 沿现有双确认链继续；
- `NOT_ESTABLISHED` 到独立结束事件，不进入派单/整改，不伪装成验收通过。

流程引擎只执行命令服务已验证的决定；不能让流程变量自行绕过 DB 状态。新建记录和显式初始化的旧 `DISCOVERED` 记录使用 V2。关闭命令核对 tenant-scoped workflow binding 的 definition key；仅 `findingRemediationV2` 可以关闭。V1 活跃实例可以继续批准路径，但不成立关闭会在领取幂等回执前以 409 `COMMAND_CONFLICT` 拒绝。

对已运行的 V1 流程不可静默切换实例。发版必须先部署 V2、再应用 V10、最后部署会绑定 V2 的应用代码。无法确认 V2 或 V10 已就绪时，不得部署新应用版本；不能回退到 V1。

## 6. 测试先行的小批次

以下是实现规格，不代表本任务已运行测试。每批不超过一天；先补失败测试，再实现，再跑该批测试。

| 批次 | 估时 | 先写的测试与通过条件 |
|---|---:|---|
| A. 确认人隔离与每次授权复核 | 0.5 天 | **A1 已实现并通过**：测试上报人分别调用 PERSON/SAFETY 被拒；PERSON 与 SAFETY 指派重复、任一确认人等于上报人时创建/旧 `DISCOVERED` 初始化均被阻断且不产生业务副作用；SAFETY 缺少 PERSON 历史被阻断；故意把流程任务改派给上报人或同一 PERSON 确认人，命令服务仍拒绝。先运行旧实现得到 5 个新增拒绝测试失败，再实现，定向类 23/23 通过，全 `backend` 测试 169/169 通过。**后续 A2 未实现**：撤权/改派并发与回执重放、任务 assignee 变化时的动态授权、完整状态/transition/audit 不变及 HTTP actor 覆盖测试仍需补齐。 |
| B. 确认输入与不成立终态 | 1 天 | **本切片已实现并通过本地测试**：关闭命令必填非空意见、PERSON/SAFETY 阶段规则和互斥；关闭不接收等级并保留原等级；普通 SAFETY 批准仍必须提供等级。终态不可重开、V10 静态迁移契约、V2 BPMN 双阶段显式关闭分支、V1 关闭阻断、新证据同租户关联、API/OpenAPI/前端状态、幂等/Unicode 回执兼容和回滚测试。真实 PostgreSQL/Flowable 集成未跑。**未实现**：`RETURN_FOR_INFORMATION` 及补充周期。 |
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
2. **已批准并在本地实现：** `NOT_ESTABLISHED` 为终态；新证据创建独立 finding 并通过可选单个 `relatedFindingId` 关联旧记录。多关联需要另行决策后改为关系表。
3. **V1 在途兼容策略已定稿、尚未部署验证：** V1 批准继续；V1 不成立关闭明确阻断。V2 BPMN 与关闭 API 在目标环境部署前需按规定顺序验证。仅静态 XML/测试替身结果不能代替 Flowable 集成测试。
4. **兼容的未知等级记录** 不能靠迁移猜测。应只读统计并保持旧值；若业务要求旧整改件补等级，另作有审计的分类批次。
5. `finding_confirmation` 现有唯一键可容纳本切片的每隐患每阶段一次结果；`RETURN_FOR_INFORMATION`/重复复核仍需追加历史模型，V5 不应被改写。

## 8. 非目标

本关闭切片不实现退回补充、多轮复核周期、V1 活跃实例迁移、账号模型、通知传输、附件/NAS、密钥或部署；不应用 V10，也不部署 V2 BPMN。审批/审核失败不得写成已成功；任何流程外紧急处置仍不可因内部审批等待而被界面描述为已通知或已办结。
