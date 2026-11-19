# 整体架构、功能路线与详细设计方案（2026-11-15）

状态：**提案 v2，业务决定已确认，工程项按阶段验证**。本文不改写 BR-001 至 BR-012，也不推翻已定 ADR；它回答的是"按现在的实现走下去，哪里不符合需求，应该怎么调"。

v2 已并入[设计确认稿](software-factory-design-interview-2026-11-15.md)及第 8 节的七条修正。Q1 至 Q15 现已全部由用户确认，工装借还的对象模型、责任规则与办理链以确认稿第 4 节为准，本文不重复。新增的工程候选项仍按第 7 节分别核验，不由业务问答推定已验证；功能验收见[验收清单](software-factory-ledger-acceptance-2026-11-15.md)。后续执行证据见 [D0 收敛验收](d0-closure-verification-2026-11-15.md)与 [D1 台账验收](d1-closure-verification-2026-11-15.md)；下文现状和代码规模保留为访谈时快照，不能作为执行后的状态。

## 0. 结论

1. **先收敛，再开发。** 仓库里有两条互相冲突的开发线，迁移版本号和表名都撞了。不先定出唯一基线，后面每一步都在加大合并成本。
2. **技术选型不用动。** Java 21 模块化单体、PostgreSQL、jOOQ/Flyway、嵌入式 Flowable、服务端 Session 都继续用。要调的是模块归属、台账的做法和开发顺序。
3. **下一阶段的目标是证明"新增台账和应用足够省力"（Q1）。** 标准的查询、新增、编辑、主从保存由框架提供，特殊动作由显式业务代码实现（Q3）。现在每个实体要 270 至 3500 行专用代码，这是当前架构和 BR-001/BR-002 之间最大的差距。
4. **用工装借还做第二个样板（Q4）。** 它与双重预防共同验证框架，并且用四类字段变更演练（Q8）作为出口证据，不用页面数量证明完成。
5. **双预防主线随后接回。** "危险源→风险→措施→排查→隐患→复评"在界面上还走不通，排在台账能力验证之后小步接入。
6. **硬件相关需求（BR-007/009/011/012）继续等真实材料**；事件中心和触发规则（BR-004/008）有三类触发源不依赖硬件，可以先做。

## 1. 依据与核查范围

读过的材料：`business-requirements-and-scenarios.md`、`design-baseline.md`、`architecture-decisions.md`、`overall-development-status-2026-11-03.md`、`implementation-milestones.md`、`frontend-contract-gap.md`、`permission-config-and-risk-slice-design.md`、`business-samples/dual-prevention-mechanism.md`、`logical-schema.md` 第 3 节，以及其余设计文档的目录结构。

核查过的代码：当前工作区（`codex/complete-evidence-20261108`，含未提交改动）、`claude/integration-backend-20261108`、`origin/dev`，以及各分支的迁移、模块、接口清单。

访谈分析阶段未做的事：集成分支的代码只按文件清单、行数、接口和 DDL 核查，**没有逐文件读实现**；当时没有运行测试或连接数据库。第 2.3 节对 `support` 模块的判断因此标为"需复核"。后续执行的代码审阅和验证另见上述阶段报告。

## 2. 现状判断

### 2.1 两条开发线

| | 线 A：`claude/integration-backend-20261108` | 线 B：当前工作区 |
|---|---|---|
| 状态 | 已提交已推送，领先 `origin/dev` 125 个提交；有 116 个提交不在线 B | 有 2 个提交不在线 A（V18.1 至 V18.3 可信证据发布），另有 48 个文件未提交 |
| V19 | `identity_lifecycle_capabilities`（邀请、找回、凭证挑战） | `configurable_permission_catalog`（权限目录、变更日志） |
| V20 | `session_sensitive_actions`（会话管理、再认证） | `risk_control_read_model`（`hazard_source`、`risk_scenario`、`risk_hazard`、`risk_assessment`、`control_measure`、`risk_control`） |
| V21 | `asset_hazard_foundation`（工房、产线、环节、设备、作业单元、`hazard_source`，全部带修订表） | `inspection_task_read_model` |
| V22 至 V24 | `risk_scenario` 草稿与修订、租户审计查询、风险变更原因 | 无 |
| 资产与风险 | 可写、带修订历史和幂等回执；模块在 `dualprevention/asset`、`risk`、`hazard` | 只读加开发库样例；独立顶层模块 `riskcontrol`、`inspection` |
| 权限码 | 按实体：`risk_scenario.read`、`equipment.create` 等约 30 个 | 按功能：`risk.read`、`inspection.read`、`platform.permission.*` |
| 导航 | 写死 `safety` 应用和 `finding` 一个节点 | 菜单目录加库内角色权限驱动 |
| 权限配置 | 无 | 有（角色权限整体替换、乐观并发、末位管理员保护、审计） |
| 前端 | 只有隐患和登录 | 多了风险台账、排查台账、权限配置三个页面 |

两条线**功能互补、结构冲突**：线 A 有扎实的后端但没有页面和权限配置；线 B 有页面和权限配置，但资产与风险的表是重做的一套，表名与线 A 相同、字段不同。开发库 `might_software_factory_dev` 已按线 B 应用了 V19 至 V21。

### 2.2 需求覆盖

| 需求 | 现状 | 差距 |
|---|---|---|
| BR-001 软件工厂 | 模块边界测试、契约生成、CI 都在 | 没有"声明字段即得台账"的内核，新应用仍要复制代码 |
| BR-002 通用台账 | 隐患台账有字段目录与查询契约；线 A 有 `EntityDefinition` 发布模型 | 查询、详情、引用选择每个实体各写一套；无查询历史、个人视图、导出 |
| BR-003 平台管理 | 登录、会话、改密、邀请与找回（线 A）、角色权限配置（线 B） | 租户开通、组织岗位、成员与角色授予、范围规则都没有管理界面 |
| BR-004 组合配置 | 无代码 | 全部 |
| BR-005 设备与风险 | 线 A 后端覆盖工房至危险源、风险场景草稿 | 无页面；无风险评估、管控措施、检查项 |
| BR-006 排查与隐患 | 隐患双确认、派单、多轮整改验收、补充复核、证据上传扫描 | 排查计划、任务执行、结果填报、不合格转隐患都没有；延期、重开、通知书、紧急通知没有 |
| BR-007 至 BR-012 | 无代码 | 依赖设备、算法、媒体环境等真实材料 |

### 2.3 五个结构性问题

**问题一：没有单一主干。** `origin/dev` 停在 11 月 7 日，之后的工作分散在 20 个未合并分支和 5 个 worktree 里。迁移号在分支上各自递增，所以才会撞号。

**问题二：纵深与广度失衡。** 线 A 主代码约 3.3 万行、测试约 4.2 万行，集中在身份、证据上传、隐患链和资产的并发与恢复加固上。这些加固本身有价值，但 12 组需求里 7 组还没有一行代码，业务方能看到的界面只有一个隐患台账。

**问题三：每个实体的成本太高。** 线 A 的资产模块 5 个实体用了 38 个文件 3512 行，风险场景 1 个实体 23 个文件 2812 行，接口按实体逐个写死（`/entities/equipment/schema`、`/entities/workshop/schema` 等 21 个只读接口各有控制器方法）。线 B 的三个只读模块各自重复了控制器、异常处理、模型、仓储、服务六件套。按样板设计还有至少十几个台账要做，这个成本不可持续。

**问题四：模块归属不清。**
- `authorization` 是公共模块，却放着 `FindingReadAuthorizationSnapshot`、`AssignableFindingMember`、`FindingWorkflowActionAssignmentPolicy` 等隐患专用类。
- 线 A 的 `support` 模块有约 3850 行以任务编号命名的基础设施（`G014JdbcGuard`、`RiskNativePublicationTransactions`、`NativeRiskRecovery` 等），应用根包下还有 `RiskScenarioTomcatProfile`、`RiskScenarioHeaderBudget`。主数据的增删改是否需要专用事务管理器和恢复桥，**需复核**；至少它们不该以任务编号的名字留在公共模块。
- 线 B 把 `riskcontrol`、`inspection` 做成与 `dualprevention` 平级的顶层模块，把一个业务域拆成了三个。

**问题五：前端按页面类型硬编码。** `App.tsx` 用 `if (route.page.kind === ...)` 逐个分支渲染，每个功能有"接口定义、HTTP 实现、合成实现"三个适配器文件和一个手写页面。零运行时依赖和手写路由可以保留，但台账页必须改成由后端定义驱动。

## 3. 目标架构

### 3.1 分层

```
┌──────────────────────── 浏览器（React，零运行时依赖）────────────────────────┐
│  壳层：登录 / 应用切换 / 菜单        通用台账页        专用页（隐患办理、权限配置） │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │ 同源 HTTPS，Session Cookie + CSRF
┌──────────────────────────────────┴─────────────────────────────────────────┐
│ 业务应用层                                                                   │
│   safety（原 dualprevention）: asset · hazard · risk · control ·             │
│                                 inspection · finding                         │
│   tooling（D1 起）  eventcenter（D8 起）  device / map / dashboard（D9 起）  │
├─────────────────────────────────────────────────────────────────────────────┤
│ 平台内核层                                                                   │
│   identity · tenancy · directory · authorization · platformadmin            │
│   applicationregistry（定义目录 · 导航）    ledger（通用台账运行能力）          │
│   workflow · files · auditops · notification（D7 起）                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ PostgreSQL 18（业务表 · Flowable · Session · 事件发布登记）                    │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │ HTTP 回调 + 幂等键（D9 起，材料到位后）
                        独立执行面：抽帧 / 数据处理 / 算法 / 证据标注
```

规则只有三条：业务层可以依赖内核层，反过来不行；业务模块之间只通过公开接口或持久事件协作；执行面不直连业务库。

### 3.2 模块调整

| 现在 | 调整 | 理由 |
|---|---|---|
| 线 B `riskcontrol`、`inspection` 顶层模块 | 并入 `dualprevention` 的 `risk`、`inspection` 子包 | 一个业务域一个模块，域内可以同事务关联 |
| `authorization` 里的隐患专用类 | 移到 `dualprevention/finding`，`authorization` 只留通用的"权限码加范围"查询 | 公共模块不认识具体业务 |
| `support` 里的 G014 与风险专用基础设施 | 复核后二选一：通用的改名留在 `support`，专用的移进 `dualprevention` 或删除 | 公共模块不带任务编号 |
| 应用根包下的 `RiskScenario*`、`AssetFoundation*` 配置类 | 移入所属模块 | 根包只放启动类 |
| `platformadmin`（线 B） | 保留，后续承接成员角色授予、组织管理 | 已是合理边界 |
| 新增 `ledger` | 通用台账运行能力：查询、标准新增编辑、主从保存、引用查询 | `applicationregistry` 只管定义与导航登记，不同时承载业务持久化 |
| 新增 `tooling` | 工装档案与借还业务 | 第二个样板，不塞进安全业务模块（Q4） |
| 新增 `notification`（D7） | 站内通知与投递记录 | BR-006 紧急通知、BR-008 事件中心都要用 |

不新增的东西：微服务、消息队列、Redis、对象存储。理由见已有 ADR，仍然成立。

### 3.3 通用台账能力

目标：**在台账能力冻结后，新增一个普通台账或做 Q8 的四类字段变更，不为该实体写专用控制器和表单，也不修改通用前后端源码去识别实体名称。** 业务建模、DDL、映射和业务测试仍然要做，"省力"不等于"不用做"。

职责分三块：

| 职责 | 归属 | 内容 |
|---|---|---|
| 定义与注册 | `applicationregistry` | 版本化的应用、实体、字段、动作、引用、导航。定义是描述，不授权也不执行 |
| 通用运行能力 | `ledger`（新） | 按登记定义执行查询、标准新增编辑、主从保存、引用候选查询 |
| 业务规则与特殊动作 | 各业务模块 | 自己的模型、状态前提、显式命令 |

线 A 已有发布模型 `EntityDefinition`（字段、布局、动作、引用，`Use` 只有 READ 和 CREATE）。需要补的部分：编辑场景、从属明细、历史冻结声明、定义版本兼容信息。

业务模块通过一个服务端绑定接口接入。绑定不向浏览器暴露表名、列名或可执行表达式：

```java
/** 由业务模块实现，放在它自己的包里。ledger 只读取，不反向依赖业务。 */
public interface EntityLedgerBinding {
    EntityDefinition definition();            // 字段、场景规则、引用、明细、动作、历史
    Table<?> table();
    Map<String, Field<?>> columns();          // 字段键 → jOOQ 列
    Set<String> protectedFields();            // 通用输入不可写：tenant、actor、id、version、状态、占用等
    ScopePolicy scope();                      // 数据范围，见下
    default WriteGuard writeGuard() { return WriteGuard.NONE; }   // 标准保存前的业务守卫
}

/** 数据范围不止组织列：本人、部门、业务责任关系都由业务模块给出条件。 */
public interface ScopePolicy {
    Condition visible(CurrentTenantContext context, String permission);
}
```

`ScopePolicy` 是访谈稿修正 3 的落点：只有一个组织列做不了 Q12（普通人员看本人单据，主管看本部门，保管人看负责范围）。列表、计数、候选、详情、写入、附件、导出必须用同一个范围条件；多角色的范围取并集，租户隔离与业务禁令照常生效。

通用接口：

| 接口 | 行为 |
|---|---|
| `GET /api/v1/entities/{entityKey}/definition` | 返回定义与版本，带 ETag |
| `POST /api/v1/entities/{entityKey}/query` | 会话 → 动作许可 → 范围条件 → 按定义校验查询 → 分页 |
| `GET /api/v1/entities/{entityKey}/{id}` | 同样的授权链；不可见与不存在统一 404 |
| `POST /api/v1/entities/{entityKey}` | 标准新增；含从属明细时主从同事务 |
| `POST /api/v1/entities/{entityKey}/{id}/update` | 标准编辑；带 `expectedVersion` 与 `definitionVersion` |
| `POST /api/v1/entities/{entityKey}/references/{referenceKey}/query` | 引用候选，受引用权限与范围约束 |

约束：
- `entityKey`、字段键、引用键只能命中已登记内容，未登记一律 404 或 400。不接受来自请求的表名、列名、表达式。
- 标准保存只写定义允许的字段。受保护字段出现在输入里直接拒绝，不静默忽略。
- 聚合并发版本与定义版本分别检查。未知字段、不支持的定义版本给出明确错误，前端保留用户输入并提示刷新。
- 标准保存不是动态命令分发器。提交、发放、归还、接收、确认、验收这类改变业务状态的动作，继续由业务模块的显式命令实现；它们可以复用内部的校验和存储组件，但有自己的授权、状态前提和事务边界。
- 定义里的校验提示只改善交互，完整规则在服务端执行。
- 已有的隐患接口不先做替换，按第 5 节的顺序小步适配，双确认、证据封存和既有事务行为保持独立验收。

需要区分的五种版本：数据库迁移版本、定义版本、聚合并发版本、业务修订、流程绑定版本。已冻结的单据按它当时绑定的定义版本解释，当前字段变必填不回写历史。

### 3.4 命令分级

所有业务写入都继承既有安全事务协议：ADR023 的 root→tenant 授权锁序、锁后读取授权、幂等、审计与事件同事务。分级区分的是**在这之上还要加什么**，不是省掉什么：

| 级别 | 适用 | 在公共协议之上 |
|---|---|---|
| T1 流程与安全关键 | 隐患流程命令、授权与身份写入、风险发布、工装整单发放 | 流程推进或多行原子占用、绑定锁、提交结果未知时的恢复。现有实现保持不动 |
| T2 主数据与业务台账 | 资产、危险源、措施、检查表、工装档案、借用申请草稿 | `expectedVersion` 乐观锁、`Idempotency-Key`、修订行。走 `ledger` 的标准保存 |
| T3 个人偏好 | 查询历史、个人视图、列设置 | 普通写入，按成员隔离 |

可靠性按动作后果判断，不按它叫不叫"台账"判断。

T2 新增实体共用一张 `command_receipt`（`tenant_id, command_id, entity_key, entity_id, request_hash, response jsonb, created_at`，`UNIQUE(tenant_id, command_id)`）。线 A 已有的回执表、提交未知恢复、事件原子性实现**先保留**；只有在证明标准实现可以等价替代之后，才考虑归并或删除，不因为代码多就删。

### 3.5 权限与导航统一

- 权限码统一为线 A 的 `{实体}.{动作}`。线 B 的 `risk.read` 改为 `risk_scenario.read`，`inspection.read` 改为 `inspection_task.read`，`platform.permission.*` 保留。
- 线 B 的权限目录列（`application_key`、`label`、`sort_order`）保留，再加 `group_label`，让配置页能把 30 多个权限码按"资产、风险、排查、隐患、平台"分组显示。
- 菜单目录仍写在代码里，节点可见性由库内角色权限决定（线 B 的做法）。应用入口的条件：租户已启用该应用，且至少一个节点可见。
- 权限逐请求读库，变更即时生效（现状，保留）。

### 3.6 事件、通知与任务

持久事件直接用已经引入的 Spring Modulith JDBC 事件发布登记，不另建 outbox 表。约定：
- 业务事实在业务事务内发布；监听器在提交后执行，失败可重投；监听器必须幂等。
- 外部副作用（通知投递、文件扫描、回调）只在监听器里做，不在业务事务里做。
- 事件类型用稳定字符串键加版本。载荷保留最小的不可变业务事实（对象 ID、版本、前后状态、操作者、时间）。监听器需要更多字段时，按载荷里的版本号读对应的历史修订，不用当前值重新解释旧事件。

### 3.7 执行面边界

设备、算法、媒体环境未定，现在只定契约不写实现：
- 执行面是独立进程，语言不限（证据标注已定用 Python）。
- 入站只有一个入口：`POST /api/v1/ingest/results`，带来源标识、`Idempotency-Key`、结果模式版本；平台校验后落"结果"，再经规则变成事件。
- 平台不向执行面推送指令，执行面按配置版本号轮询拉取任务配置。这样执行面不可用时管理端不受影响。

### 3.8 前端

保留：React 19、零运行时依赖、手写路由、适配器隔离、契约生成类型。

调整：
1. **路由注册表**代替 `App.tsx` 的分支链。每个功能导出 `{ path, permission, render }`，`App.tsx` 只查表。
2. **通用台账页** `EntityLedgerPage`：输入 `entityKey`，读取定义后渲染查询区、表格、详情抽屉。复用现有 `LedgerQueryForm`、`LedgerTable`。风险、排查、设备这类台账不再写专用页。
3. **通用适配器** `httpEntityLedger(entityKey)`：一个实现服务所有台账，取代每个功能一组三个文件。合成演示模式只保留隐患一条线，其余台账在演示模式下显示"需连接后端"。
4. **专用页只留给有流程的地方**：隐患办理、排查任务执行、权限配置。
5. 目录按功能收拢：`features/<功能>/` 下放页面、适配器、测试；`components/` 只放跨功能复用的组件。

## 4. 两条线的收敛方案

以线 A 为底座，理由：已提交、已推送、有 PG18 验收记录，且是可写模型。线 B 的权限配置、菜单驱动、三个页面作为增量移植上去。

### 4.1 迁移重排

> D0 核查后的更新见 [D0 收敛方案](d0-convergence-plan-2026-11-15.md)：V18 必须取线 B 的文本；V20、V21 改为暂不移植，由 D5、D6 按新设计建表。下表的 V26、V27 两行以该文为准。

| 线 B 现有 | 处理 | 新版本 |
|---|---|---|
| V18.1 至 V18.3 | 保留编号，合入线 A。NAS 验收库已到 V18.3，接着应用线 A 的 V19 至 V24 是顺序的 | 不变 |
| V19 权限目录 | 基于线 A 的权限行重写：加列、补标签与分组、建变更日志表、加 `platform.permission.*` | V25 |
| V20 风险只读模型 | 丢弃 `hazard_source`、`risk_scenario`、`risk_hazard` 三张表，用线 A 的；`risk_assessment`、`control_measure`、`risk_control` 改为引用线 A 的表后保留 | V26 |
| V21 排查只读模型 | 保留，并补检查表版本与排查计划 | V27 |
| `db/dev-seed`、`db/operator` | 按新表结构重写 | 随上 |

防复发的规则只加一条：**迁移版本号在合入主干时分配**，CI 增加一项"迁移版本号重复即失败"的检查。

### 4.2 步骤

以下是收敛设计，执行前逐项确认，不是已授权的操作。

1. 把线 B 当前未提交的工作提交到独立分支留底，不合并。
2. 逐项比较两条线的公共契约、迁移和权限码，产出合并映射与冲突清单（4.1 是初稿，按同名表的字段和行为差异补全）。
3. 核验各数据库的实际迁移历史与数据：开发库 `might_software_factory_dev`、NAS 验收库、各隔离测试库分别到哪个版本、有哪些非样例数据。
4. 在**新建的隔离库**里验证收敛后的迁移序列能从空库跑通，并验证线 B 的权限配置、菜单目录、三个页面移植后可用。
5. 定出唯一候选基线并合入 `dev`；其余分支只保留有未合并内容的。
6. 现有开发库怎么处理放在第 3 步核验之后决定。它已应用线 B 的 V19 至 V21，与线 A 的同号迁移不兼容，无法原地升级；可选方案是导出需要保留的数据后重建，或保留原库另建新库。**不把清空或重建当作默认动作。**

## 5. 功能路线

每个阶段以证据为出口：真实 API、真实数据库、真实浏览器分别记录，没跑的记 NOT_RUN。不给工期。D0 至 D4 与访谈稿第 6 节一致，D5 起是后续总需求的排序。

| 阶段 | 内容 | 需求 | 出口 |
|---|---|---|---|
| D0 契约与分支收敛 | 第 4 节；模块归属调整（3.2） | — | 唯一候选基线；兼容与冲突清单；隔离库可验证的迁移路径；原库保留策略 |
| D1 标准读写贯通 | 定义补齐读取、创建、编辑；`ledger` 模块与服务端绑定；通用台账页；以**工装档案**贯通页面、校验、持久化、范围、审计 | BR-001、002 | 新增、编辑、查询经真实 API 和数据库；受保护字段、撤权、版本冲突的负例通过 |
| D2 主从与业务动作 | 借用单与明细；申请、整单发放、分批归还、接收、损坏与遗失结案 | BR-001、002 | 全链浏览器走通；并发抢同一件只有一个成功；任一明细不可借则整单不发放 |
| D3 复用与变更证明 | 台账能力冻结后登记一个简单独立台账（如工装类别）；执行 Q8 四类字段变更 | BR-001、002 | 没有为该实体修改通用层；已有数据、查询视图、旧单办理不被破坏。发现缺口就记录、改框架、换样本复测 |
| D4 小步接回安全业务 | 选一个边界清楚的现有安全台账适配，再逐个扩展；补权限配置页 | BR-005 | 既有业务行为回归通过；特殊授权和动作不被通用保存绕过 |
| D5 风险评估与管控措施 | 评估、复核、生效；措施与责任；检查表版本 | BR-005 | 从风险场景能追到当前评估、措施、责任人；缺评估显示"待评估" |
| D6 排查闭环 | 排查计划、任务生成、逐项填报、不合格转隐患、隐患关闭触发复评 | BR-006 | 一次排查从计划走到隐患关闭再到复评提醒，全链可追溯 |
| D7 隐患例外与平台管理 | 延期、重开、通知书、紧急通知；站内通知；成员角色授予、组织岗位、范围规则管理 | BR-003、006 | 租户管理员不碰数据库完成开户到授权；延期保留原期限 |
| D8 事件中心与触发规则 | 人工、计划、台账状态三类触发源；条件与动作；循环保护；影子运行 | BR-004、008 | 重复触发不重复建单；影子运行无副作用 |
| D9 接入、地图、大屏 | 设备信号、算法结果、外部 API 三类触发源；2D 地图；受控大屏 | BR-007、009、010 | 取决于真实设备、算法服务、厂区图纸 |
| D10 视频与容量 | 视频交互、证据标注、六类算法、200 并发、50 路分析、16 路显示 | BR-011、012 | 取决于媒体执行环境和代表数据 |

排序上的三个取舍：
- **台账能力先于一切新业务。** 晚做一个阶段，就多几套要返工的专用代码。
- **不把七个安全台账全部迁入当作先决条件。** 先用工装档案和借还验证真实的新增成本，再决定安全台账怎么接。
- **平台管理排在排查闭环之后。** 首个业务背景是制造业单厂、业务人员（Q2），开户和授权可以先用种子脚本。如果要给多个真实租户试用，D7 的平台管理部分应提前。

查询历史、个人视图、导出仍属于 BR-002，不因本轮优先读写而删除；D3 结束时不能宣称 BR-002 已完成。

## 6. 详细设计

工装档案与借还（D1、D2）的对象、办理链和已确认的 Q13 至 Q15 见设计确认稿第 4 节；6.0 给出与之对应的候选表结构。本节其余部分覆盖权限目录（D4）、风险评估（D5）、排查（D6）和前端。

### 6.0 工装档案与借还（候选表结构）

Q6、Q9 至 Q15 确认后可以落到表上。以下是候选：表名、状态码和迁移编号在 D0 契约与迁移收敛核验后固定，保管范围是否复用现有目录实体也在 D0 核验。全部带 `tenant_id`、`version`，启用行级安全，归 `tooling` 模块。

| 表 | 关键字段 | 约束与对应决定 |
|---|---|---|
| `custody_scope` | `code, name, state` | `UNIQUE(tenant, code)`；不默认等于部门 |
| `custody_scope_keeper` | `scope_id, member_id, effective_from, revoked_at?` | 部分唯一索引 `(tenant, scope, member) WHERE revoked_at IS NULL`；办理资格每次命令读当前有效行，不读历史（Q13、Q14） |
| `tool` | `code, name, model?, custody_scope_id, item_state` | `UNIQUE(tenant, code)`（Q6）；`item_state` 取 `NORMAL/DAMAGED/LOST/RETIRED`；**没有"可借/在借"列**，由 `item_state` 与占用事实派生；存在未结清明细时拒绝改 `custody_scope_id`（Q14） |
| `tool_loan` | `borrower_member_id, department_id_at_submit?, custody_scope_id, definition_version?, revision, state` | `state` 取 `DRAFT/PENDING_ISSUE/ISSUED/SETTLED/WITHDRAWN`；提交时写入部门、范围和定义版本，之后不回写（Q14、Q15） |
| `tool_loan_revision` | `loan_id, revision, items jsonb, actor_member_id, created_at` | `UNIQUE(tenant, loan, revision)`；只追加；发放前每次调整明细追加一行（Q10、Q15） |
| `tool_loan_item` | `loan_id, tool_id, issued_at?, settlement?, settled_at?` | `UNIQUE(tenant, loan, tool)`；`settlement` 取 `RETURNED/DAMAGED/LOST`；部分唯一索引 `(tenant, tool_id) WHERE issued_at IS NOT NULL AND settled_at IS NULL` 就是占用事实，同件只能有一行（Q9） |
| `tool_return_batch` | `loan_id, requested_by, requested_at` | 一次归还申请一行（Q10） |
| `tool_return_batch_item` | `batch_id, loan_item_id, outcome?, handled_by?, handled_at?, note?` | `outcome` 取 `RECEIVED/DAMAGED/CLOSED_LOST`；部分唯一索引 `(tenant, loan_item_id) WHERE outcome IS NULL`，同一明细不能有两个待接收请求 |
| `tool_loan_event` | `loan_id, loan_item_id?, action, actor_member_id, reason?, occurred_at` | 只追加；运行账号只有 INSERT 和 SELECT；发放、接收、损坏、遗失结论都记在这里（Q11） |

占用不单独建表：已发放未结清的明细行就是占用，由部分唯一索引保证唯一，少一处需要保持同步的状态。

显式命令全部属于 T2，继承 ADR023 的锁序、幂等回执和 `expectedVersion`：

| 接口（候选） | 权限码 | 命令内校验 |
|---|---|---|
| `POST /api/v1/tool-loans`、`/{id}/update`、`/{id}/submit`、`/{id}/withdraw` | `tool_loan.create` | 仅本单借用人；同一保管范围；未发放 |
| `POST /api/v1/tool-loans/{id}/issue` | `tool_loan.issue` | 当前同范围有效保管人且**不是借用人**（Q13）；按 `tool_id` 升序锁定全部工装，任一 `item_state ≠ NORMAL` 或已被占用则整单回滚（Q9、Q10）；请求携带的 `revision` 必须是当前修订 |
| `POST /api/v1/tool-loans/{id}/return-requests` | `tool_loan.create` | 仅本单借用人；选中的明细已发放、未结清且无待接收请求 |
| `POST /api/v1/tool-return-items/{id}/receive` | `tool_loan.receive` | 同 Q13 的办理人规则；`outcome=DAMAGED` 时同事务把工装置为 `DAMAGED` |
| `POST /api/v1/tool-loan-items/{id}/close-lost` | `tool_loan.close_lost` | 同 Q13 的办理人规则；同事务把工装置为 `LOST` 并终结关联的待接收请求 |

- 没有第二名有效保管人时，办理命令返回明确的"无人可办"拒绝，不降级给主管或借用人本人；多角色不绕过。
- 全部明细结清后借用单转 `SETTLED`；单据结清不恢复 `DAMAGED/LOST` 工装的可借资格，恢复需要以后另加的授权动作。
- 标准编辑的受保护字段：`tool.item_state`、`tool_loan` 与 `tool_loan_item` 的全部状态和时间列。
- 读取走通用台账接口，实体键 `tool`、`tool_loan`；`ScopePolicy` 给出本人、提交时部门、当前负责保管范围三种条件（Q12、Q14）。

### 6.1 权限目录与角色配置

```sql
alter table public.permission
    add column application_key varchar(32),
    add column label           varchar(64),
    add column group_label     varchar(32),
    add column sort_order      integer not null default 0;

create table public.permission_change_event (
    tenant_id   uuid        not null,
    id          uuid        not null,
    role_id     uuid        not null,
    actor_id    uuid        not null,
    added       text[]      not null,
    removed     text[]      not null,
    occurred_at timestamptz not null,
    primary key (tenant_id, id)
);  -- 启用行级安全；运行账号只有 INSERT 和 SELECT
```

接口沿用线 B 已实现并联调过的三个：`GET /api/v1/admin/permissions`、`GET /api/v1/admin/roles`、`PUT /api/v1/admin/roles/{roleId}/permissions`。写入属于 T1：走 `ManagedIdentitySecurityTransaction`，保留乐观并发、目录外权限码拒绝、末位管理员保护。

### 6.2 风险评估与管控措施

表引用线 A 的 `risk_scenario`、`hazard_source`，全部带 `tenant_id`、`organization_id`、`version`，启用行级安全。

| 表 | 关键字段 | 约束 |
|---|---|---|
| `risk_assessment` | `scenario_id, revision, method_ref, input jsonb, grade?, assessor_member_id, reviewer_member_id?, published_at?, next_review_at?` | `UNIQUE(tenant, scenario, revision)`；`grade` 取 `RED/ORANGE/YELLOW/BLUE` 或空；`published_at` 非空后**内容列**不可改（触发器拒绝） |
| `risk_assessment_status` | `assessment_id, state, effective_from?, superseded_at?, superseded_by?` | 生命周期单独成表，与内容 1:1；每个场景同一时刻至多一行 `EFFECTIVE`（部分唯一索引） |
| `control_measure` | `code, description, category, responsible_position_id?, responsible_member_id?, state` | 责任岗位与责任人至少一项；`category` 取 `TECHNICAL/MANAGEMENT/PROTECTIVE/EMERGENCY` |
| `risk_control` | `scenario_id, measure_id` | 多对多；删除关联不删措施 |
| `reassessment_task` | `scenario_id, cause, cause_ref?, state, created_at` | `cause` 取 `FINDING_CLOSED/MEASURE_FAILED/ASSET_CHANGED/DUE/MANUAL`；只是提醒，不改评估 |

评估状态机：`DRAFT → PENDING_REVIEW → EFFECTIVE → SUPERSEDED`，复核退回回到 `DRAFT`。发布内容与生命周期分开存：发布后内容行永不更新，新评估生效时只更新旧评估的状态行，同事务完成。评估人与复核人不得是同一成员。评估发布属于 T1。

业务规则直接取自样板，不新增：
- 场景没有 `EFFECTIVE` 评估时等级为空，界面显示"待评估"，统计单列，不计入低风险。
- 等级由评估人填写并经复核，平台不内置公式和阈值（规则包未核定前）。
- 措施失效、隐患关闭、资产变更只生成 `reassessment_task`，不自动改等级。

显式命令接口（评估的提交与复核属于 T1，其余 T2）：

| 接口 | 权限码 |
|---|---|
| `POST /api/v1/risk-scenarios/{id}/assessments` | `risk_assessment.create` |
| `POST /api/v1/risk-assessments/{id}/submit`、`/review` | `risk_assessment.create`、`risk_assessment.review` |
| `POST /api/v1/control-measures`、`/{id}/update` | `control_measure.create`、`control_measure.update` |
| `POST /api/v1/risk-scenarios/{id}/controls`、`/{measureId}/remove` | `risk_scenario.update` |

读取全部走通用台账接口，实体键 `risk_assessment`、`control_measure`、`reassessment_task`。

### 6.3 排查

| 表 | 关键字段 | 约束 |
|---|---|---|
| `checklist_version` | `key, revision, state, title` | `UNIQUE(tenant, key, revision)`；`PUBLISHED` 后不可改 |
| `checklist_item` | `checklist_version_id, ordinal, criterion, result_type, evidence_required, measure_id?` | `result_type` 取 `PASS_FAIL/NUMERIC/TEXT`；可追到来源措施 |
| `inspection_plan` | `code, checklist_version_id, scope_work_unit_id, assignee_position_id, cycle, next_due_at, state` | `cycle` 取 `DAILY/WEEKLY/MONTHLY/QUARTERLY`；首版不做任意 cron |
| `inspection_task` | `plan_id, generation_key, checklist_version_id, assignee_member_id, due_at, state` | `UNIQUE(tenant, generation_key)`，生成幂等 |
| `inspection_result` | `task_id, item_id, result_kind, value jsonb?, note?, evidence_set_id?, checked_at, finding_id?` | `UNIQUE(tenant, task, item)`；`result_kind` 取 `PASS/FAIL/NOT_APPLICABLE` |

线 B 现有的 `inspection_item` 直接挂在任务下，改为挂在 `checklist_version` 下，任务引用固定版本。

任务状态机：`PENDING → IN_PROGRESS → SUBMITTED`；`OVERDUE` 是按 `due_at` 计算出的显示状态，不落库。提交条件：每个检查项都有结果行。**没有结果行的项不算合格**，不满足时拒绝提交并返回缺失项。`NOT_APPLICABLE` 必须填 `note`。

任务生成：`@Scheduled` 每小时扫描到期计划，按 `plan_id + 周期起始日` 生成 `generation_key` 后插入，唯一约束保证多实例不重复。

```java
// ponytail: 单表扫描加唯一约束去重，够用到每租户几千个计划；量大后改按 next_due_at 分片
```

指派岗位下没有有效成员时，任务照常生成但 `assignee_member_id` 为空，进入"待指派"并通知安全部门，不随意选人。

不合格转隐患：`POST /api/v1/inspection-results/{id}/finding`，权限 `finding.create`。在同一事务里调用现有隐患创建服务，并把 `finding_id` 写回结果行。结果行上已有 `finding_id` 时返回原隐患，所以同一结果重复提交不会产生两条隐患。隐患一侧新增可空列 `source_inspection_result_id`，隐患详情可以反查到检查项、措施、风险场景。

隐患关闭后的复评：隐患模块发布已有的状态变更事件；`risk` 子包监听，若该隐患经检查项关联到措施和风险场景，则为每个场景插入一条 `reassessment_task`（`cause=FINDING_CLOSED`）。监听器按 `(scenario_id, cause, cause_ref)` 去重。

检查表、计划的新增编辑走标准保存；下表是显式命令：

| 接口 | 权限码 |
|---|---|
| `POST /api/v1/checklists`、`/{id}/publish` | `checklist.manage` |
| `POST /api/v1/inspection-plans`、`/{id}/update`、`/{id}/pause` | `inspection_plan.manage` |
| `POST /api/v1/inspection-tasks/{id}/assign` | `inspection_task.assign` |
| `PUT /api/v1/inspection-tasks/{id}/results/{itemId}` | `inspection_task.execute`，且当前成员是任务执行人 |
| `POST /api/v1/inspection-tasks/{id}/submit` | 同上 |
| `POST /api/v1/inspection-results/{id}/finding` | `finding.create` |

### 6.4 前端

```
frontend/src/
  app/            App.tsx、路由注册表、壳层
  ledger/         EntityLedgerPage（列表、查询、新增编辑、详情、主从）、httpEntityLedger、定义解析与缓存（按租户和成员隔离）
  features/
    findings/     现有，专用页
    inspection/   InspectionTaskPage（执行填报，专用页）；计划与检查表走通用台账
    risk/         AssessmentPanel（评估与复核，挂在风险场景详情里）
    tooling/      借还办理（发放、归还、接收，专用页）；工装档案走通用台账
    platform/     PermissionConfigPage
    session/
  components/     跨功能组件
```

通用台账页的行为由定义决定：`layout.query` 渲染查询区，`layout.list` 渲染列，`layout.detail` 渲染详情抽屉，`actions` 里当前会话有权限的动作渲染为按钮。`layout.create` 驱动新建表单，字段控件按 `uiKind` 选择，引用字段用现有的 `OrganizationField` 模式远程搜索。

定义解析器只校验协议结构和受支持的类型，不把任何实体的字段集合写死；遇到不认识的 `uiKind` 时该字段只读显示并提示，不让整页失败。

错误处理沿用现有约定：401 回登录页并保留草稿；403 显示无权；409 区分版本冲突与幂等冲突；HTTP 模式下不回退合成数据。

### 6.5 测试与验收

每个台账或命令至少有：
- 一个离线测试覆盖校验与授权分支。
- 一个实库用例覆盖跨租户不可见、范围外不可见、撤权后拒绝。
- 可写命令加：同键同参重放返回原结果，同键异参冲突，旧版本冲突。

通用台账能力自身的公共测试只写一次；各实体的 `EntityLedgerBinding` 用一个参数化测试统一验证"定义里的每个字段都有列映射、权限码存在于权限目录、受保护字段不在可写布局里"。每个实体的范围策略、特殊规则和升级行为仍要单独验证。

每个阶段结束前，用测试租户的四个角色账号在真实浏览器里走一遍，截图入 `docs/verification/`。

## 7. 待你确认

已由访谈确认、本文照此执行的：Q1 至 Q15。

最后一轮已确认的规则（确认稿 4.3）：
- **Q13 保管人本人借用**：可申请；发放、接收、遗失结论由同范围另一名有效保管人办理；没有第二人时进入"无人可办"，不自办。
- **Q14 调岗与保管范围变化**：旧单保留申请时的部门和范围；在借工装结清后再调整范围。
- **Q15 字段发布后的旧申请**：提交时绑定字段版本，旧申请继续按原版办理；发放前仍可调整明细，保留修订并重校验；新必填不阻断旧单归还。

本文新增、需要你点头的：
1. **以线 A 为收敛底座**（第 4 节）。只确认方向；数据库怎么处理在核验实际迁移历史后单独确认。
2. **新增 `ledger` 和 `tooling` 两个模块、把 `riskcontrol`/`inspection` 并回 `dualprevention`**（3.2）。
3. **D5 之后的排序**（第 5 节）：风险评估 → 排查闭环 → 平台管理 → 事件中心。

## 8. 明确不做

- 不引入微服务、消息队列、Redis、对象存储集群、在线表单设计器、拖拽流程画布。
- 不在通用台账能力里做动态命令分发，不接受来自请求的表名、列名和表达式。
- 不抽象通用库存引擎；工装的占用规则由 `tooling` 模块自己实现。
- 不删除线 A 已有的事务恢复、撤权并发和可信证据保障，除非先证明有等价替代。
- 不内置风险评估公式、重大隐患判定规则和标准代码表，直到来源全文和适用性核定。
- 不为 BR-007、009、011、012 写模拟实现冒充接入。
- 不迁移线 A 已有且可用的回执表，不整体替换隐患接口，只约束新代码并小步适配。
- 不把重建现有数据库当作默认动作。
