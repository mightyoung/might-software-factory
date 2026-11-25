# AU-08 旧版本借用单的当前授权

`acceptance.py` 使用专用回环 PostgreSQL 数据库 `msf_au08_test` 和实际 HTTPS API。准备独立的合成租户 `d1seed`、账号 `d1seed-reader`/`d1seed-keeper`、相应角色和保管资格、已部署并固定版本的工装流程，以及当前 V35 数据库结构与运行账号授权。操作账号须能观察数据库阻塞关系，并在该专用库维护合成账号授权。每次准备创建独立工装和借用单。

通过环境提供 `PGHOST=127.0.0.1`、`PGPORT`、`PGDATABASE=msf_au08_test`、`PGUSER`、`PGPASSWORD`、`AU08_ACK=isolated:msf_au08_test`、`AU08_ORIGIN=https://127.0.0.1:<port>`、`AU08_CA`、`AU08_EVIDENCE`、`D2_PASSWORD_READER` 和 `D2_PASSWORD_KEEPER`。Python 需要 requests 和 psycopg2。CA 文件须信任本次 HTTPS 证书。

1. 以 `software-factory.tooling.loan-definition-version=1` 启动后端，运行 `python3 tools/d2-authz/acceptance.py prepare`，通过 API 创建 V1 待发放单。
2. 保留该数据库，切换新建发布配置为 2，运行 `python3 tools/d2-authz/acceptance.py verify`。
3. 核对 `AU08_EVIDENCE/summary.json`，保留数据库与后端产物哈希供复验。

不要用 `-O`、`-OO` 或非零 `PYTHONOPTIMIZE`，否则 Python 会省略验收断言。
脚本在优化模式下直接退出，不连接数据库也不生成验收结果。

验证逐次移除发放、归还、接收所需权限，逐次停用对应成员，并核对旧请求被拒绝。每次拒绝前后比较借用单、版本历史、成功回执、借用明细、归还明细、当前流程任务和成功审计数量，确保没有部分提交。权限与成员状态在 `finally` 恢复。

发放撤权还持有生产同源的租户事务锁，启动实际 HTTP 请求，通过 `pg_blocking_pids` 确认该请求等待撤权事务，随后提交撤权并观察拒绝。可用性用例通过另一张真实 V2 借用单占用旧单中的第二件工装，核对旧单整单发放失败；竞争单正常结清后，旧单可发放。旧单随后以一件正常、一件损坏完成归还接收，并检查损坏接收结果、四条业务修订及占用全部释放。

## 托管复验的离线合同

`python3 -B -m unittest discover -s tools/d2-authz -p 'test_hosted_contract.py' -v`
只检查目标、阶段证据和结果格式，无数据库、容器、下载或网络。该检查不会安装或运行
`acceptance.py`，不能作为实库通过证明。

后续独立托管任务须在干净的项目 GitHub-hosted Linux 签出上工作。目标固定为一次性的
`msf_au08_test`，端口只来自已核对属于本轮的回环容器映射，禁止继承外部数据库或
Docker 端点。真实两阶段必须当场核对同一签出、后端产物 SHA256、实际数据库实例，
以及应用 API 显示的定义发布版本 1 → 2。不要用命令行参数、环境变量或自报证据替代
真实容器所有权和数据库标记；实例证明必须来自应用连接的专用库。

下一片是专用任务的 bootstrap 与证据采集：checkout 不保留凭证，`contents: read`，
不使用仓库 secrets；运行时、合成 fixture 和迁移账号只在一次性库内分离，复用原有
源码迁移、工作流资源与 D0/D1/D2 权限，不放宽任何现有工具的目标白名单。将 V1 创建
和 V2 办理留在同一个数据库，用现有的 `prepare` 和 `verify`；仅上传固定验收项、
提交及迁移/产物摘要，不上传原始单据、请求、日志或凭据。流程或隔离证明缺失均失败，
只有清理确认后才能记实库通过。这里的提交不新增托管工作流。

`hosted_observation.py` 只读取临时 runner 的本地容器检查信息和回环数据库的身份元数据。
容器 ID 须来自本轮私有创建回执；校验精确 ID、作业标签、官方镜像的不可变摘要、单一
回环端口、单一匿名数据卷以及没有主机挂载或额外权限。fixture 和运行角色分别通过独立
只读登录核对真实数据库名、实例标记、PG18、固定角色、无成员继承及无 DDL 权限。该读取
不代表角色已经具备所需的最小业务权限，也不能单独证明启动的 HTTP 进程使用该数据源。

现有 bootstrap 不能直接使用这个数据库名：D0 授权文件要求 `msf_d0_*_test`，D1
授权和 `tools/d1/seed.java` 要求 `msf_d0_d1_*_test`，D2 授权文件要求
`msf_d2_*_test` 或 `msf_d0_d2_*_test`；`tools/d2/DeployIssueProcess.java`
只接受三个已命名的 D2 数据库。不要批量替换这些守卫、跳过前置检查或用管理员运行应用。
下一片须为固定 AU-08 目标提供单独受审的初始化，保留或收紧已有的迁移历史、对象所有权
和列级权限证明，并固定真实 Flowable 流程。启动器还须从已拥有的进程和受信任的回环
HTTPS 观察两个阶段，核对应用连接和证书后再运行既有场景。当前没有可启动的托管任务。

### 初始化复核清单（仅离线，不分配资源）

`bootstrap_inventory.py` 核对当前源码的 37 条应用 Flyway 迁移及固定审查输入，
记录原始文件 SHA-256。应用版本为 V2–V8、V11–V37 和 V18.1–V18.3；
不要把 P0 探针 V1 或原历史 V9/V10 塞进应用 history。应用迁移与框架初始化分开。
其中 V23 继续沿用 D0 已审查的做法：仅该迁移由专用实例的引导管理员执行，
原样运行 ownership 修复并验证归属，再由普通迁移身份跑后续迁移和 Flyway validate。
Spring Session 和 Flowable 使用 backend 现有锁定依赖创建并复查，应用以运行身份启动时禁用 DDL。

三个候选复用路径的结论：

- **直接复用 Java IT 并不成立。** `ToolLedgerPostgresFixture` 验证 D1/LAN
  profile，只种 D1 工装档案所需角色；没有 D2 申请、发放、接收、流程绑定和独立
  AU-08 fixture 的范围。可作为已有身份创建方式的审查参考，不能直接打开它的前置限制。
- **直接串联现有运维脚本也不成立。** D0、D1、D2 授权守卫要求不同数据库名；
  D1 和 D2 授权目标规则互斥，且都不接受本任务的 `msf_au08_test`。运维文档
  使用真正的独立阶段快照，不能简单通过改名、文本替换、禁用守卫或管理员连接冒用运行角色。
- **推荐做范围最小的 AU-08 专用 fixture。** 固定本库和白名单迁移原件，先验证
  ownership/实例，再由单独引导身份创建非提升的 migrator、fixture writer 和 app
  runtime。专用测试初始化必须给出可逐项对照 D0/D1/D2 原授权的显式表/列/函数权限
  清单，并在启动前检查完整有效权限及对象归属；不存在的权限不能通过通配补齐。
  仅预置现有验收所需合成租户、人员、组织、保管范围及测试角色。用迁移身份部署
  原始 `toolLoanV1` BPMN 并读回字节校验，再给只读配置提供真实租户/定义 pin。
  引导管理员只允许在该一次性实例承担 V23 等架构初始化，绝不能启动或代替应用运行身份；
  两阶段配置切换保持同实例、同后端产物和同临时证书。未完成专用授权清单、实际 pin
  和 HTTPS 验证前不建或推工作流。

清单另固定应用配置与 V25、V36、V37 的运维授权增量。这三个增量写死 dev 运行角色，
只能用于比较，不能直接对 AU-08 执行、替换角色名或作为授权清单。V25 与角色变更有关；
V36/V37 的普通台账权限不是工装借还权限，若启动或实际路径未使用，就不要移植到工装
验收角色。单独核对需要的登录、借还、会话和流程权限。

离线清单只提供审查材料，固定返回 `ready: false`。它不是新的授权来源、执行计划或已跑证明。

### 合成租户事务边界（尚未接入）

`fixture_scope.py` 不读取环境覆盖、不开连接，也不接受租户 UUID 参数。专用初始化须先在
本轮已核对的实例上创建并独立读回从实例标记派生的合成 `d1seed` 租户。运行每个直接
fixture SQL 事务时，先核对实时数据库名、实例标记、PostgreSQL 18、非提升 fixture 身份
和空租户上下文；还要确认该角色不是数据库所有者，且没有建库内对象或临时表所需权限。
再用事务内设置绑定标记派生 UUID，并按实际行级隔离读回固定 ID、名称和启用状态。不匹配
则回滚，后续 SQL 不运行。已经开始的事务及连接自动提交模式不接受。

没有已审查的初始化能提供该专用租户，因此未接入 `acceptance.py`。历史本地入口不变，
未增加从环境或命令行自报租户的路径。后续接入时，需拆开现有直接 SQL 的多个 commit
边界，每次进入新事务重新核对范围；不得在租户作用域内主动 commit，也不改 HTTP 业务
请求、并发先后或九项断言。离线模拟覆盖边界控制，不代表数据库已实测。

#### fixture 观察和变更权限审查（不含初始化或应用运行权限）

以下从 `acceptance.py` 的直接 SQL 和 `workflow_pin.py` 的只读查询倒推。
这是后续核对清单，不是授予权限的 SQL。三种身份分开：引导身份建库、迁移和种入
合成数据；应用身份仅执行正常 HTTPS 业务；fixture 身份仅做下表操作。

| 用途 | fixture 权限候选 | 不应借此授予 |
| --- | --- | --- |
| 隔离和成员核对 | `tenant(id,code,name,status)`、`account(id,login_key)`、`tenant_member(tenant_id,id,account_id)` 的 SELECT | 读取凭据、写账户、修改租户 |
| 撤权和精确恢复 | 读取 `permission(id,code)`、`role_grant(tenant_id,role_id,member_id)`、`role_permission(tenant_id,role_id,permission_id,scope_rule_id)`；后者仅 DELETE 和四列 INSERT | 修改权限目录、角色定义或角色授予；把 V25 dev 角色整表权限照搬过来 |
| 停用并恢复合成成员 | 仅 `tenant_member(status)` UPDATE | 修改 `auth_epoch`、身份归属或任意列 |
| 断言无部分提交 | 只读 `tool_loan`、`tool_loan_revision`、`tool_loan_command_receipt`、`tool_loan_item`、`tool_return_request_item`、`workflow_binding(business_id,process_instance_id)`、`act_ru_task` 及 `audit_event(resource_id,result)`；快照的 `to_jsonb` 包含整行 | 写业务表、改工作流任务、读取完整审计载荷 |
| 固定流程和阻塞证据 | 按 `read_workflow_pin` 实际引用的定义与资源列读取；只观测本库等待者 PID、当前持锁者及其阻塞关系 | 部署流程、读取 SQL 文本、授予 `pg_read_all_stats` 或 `pg_monitor` |

与现有授权对照：D0 的运行角色已区分成员 `auth_epoch` 更新与权限关联四列恢复，
不能据此把 D0 运行角色授给 fixture；V25 的权限变更记录与 V36/V37 的普通台账
不是此观察角色所需。成员和权限关联使用 V3 强制行级隔离；Flowable 表不能假定有
同样的应用租户策略。接入时由已核验的合成库隔离这些表，查询还须通过同租户
V1 单据的真实绑定关联，不能按孤立的任务 ID 读取。

预检需用专用 fixture 登录逐项验证有效权限和对象所有者，包含 PUBLIC 或其他来源
产生的间接权限；用测试确认只有预期合成成员和角色关联受影响。旧脚本按成员授予
匹配角色，尚未证明角色只给目标合成成员使用；接入前应在锁内证明成员初始状态、
候选关联及其原值，再变更并精确恢复，恢复后另起事务读回。不扩大权限来弥补
查询失败，不把这份审查清单当成真实 PostgreSQL 验收结果。

`workflow_pin.py` 验证后续部署读回：只接受本轮合成租户中唯一启用的第一版
`toolLoanV1`，要求实际部署的 BPMN 字节与源码 SHA-256 一致，并使用真实定义 ID
生成 pin。调用方必须从已核对归属的 Flowable 实例查同租户同键的全部版本，不可先过滤
停用或重复项。本合同只做离线输入与输出验证，不部署、不连接、不声明已实测。

`read_workflow_pin` 可在现有受限测试连接中读取部署数据。它先调用合成租户事务守卫，
再用 Flowable 8.0.0 映射中的定义表、资源表和部署键做只读查询；不把停用项或缺失
资源过滤成成功。需要本来就有对应只读权限的专用测试角色；未授予权限，也尚未在真实
PostgreSQL 上运行这个接缝。
