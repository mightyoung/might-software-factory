# P0 授权时钟与真实事务验收 — 2026-11-04

本批基于 PR #40 的 `92b9088f6a232ed5e12b8e503ff3425a91bdacc4`，分支为 `codex/guarded-authorization-clock-20261103`。完成范围是守卫等待后的授权时间一致性修复，以及独占本地 PostgreSQL 18.6、V14／11 条应用迁移历史上的窄验收；不是整个 P0、Finding／Flowable 业务闭环或 NAS／生产验收。实际执行证据已通过独立复核；结论仅为下述窄范围真实 PostgreSQL 授权时钟验收 PASS。

## 修复与静态复核

命令事务可能在等待授权守卫前已经开始；使用事务开始时间会使已经过期的授权在等待结束后仍被视为有效。现在每次真正评估时间条件时，由调用者的物理事务读取一次数据库时间：READ COMMITTED 使用新语句的 `statement_timestamp()`，REPEATABLE READ／SERIALIZABLE 使用原事务的 `transaction_timestamp()`。没有 JVM／客户端时钟兜底。

同一时间绑定到组织、岗位、用户组成员关系、角色授权和部门树种子；候选搜索与单成员权威检查使用同一份完整 SQL 条件和单行时间 CTE。半开时间范围保持 `valid_from <= evaluatedAt`、`valid_to > evaluatedAt`。守卫前的 READ COMMITTED 提示读取仍可用，真正写入前由既有守卫后的评估决定授权；旧公开非 At 查询保持原事务时间行为。三个新增 At 接口默认拒绝，不默默回退到旧实现。

本轮对冻结的 5 份产品源码、5 份单元测试和 1 份集成测试进行了有界静态复核：数据库时间来源、同一次评估的绑定、租户与主体条件、失败关闭、旧查询兼容、SQL 参数及测试观察边界均未发现需要修改的问题。因此没有为整理代码而改写已通过的实现，也没有引入依赖、迁移或通用时钟框架。该作者复核不替代独立代码或验收证据审查。

## 验证结果

下列运行相互覆盖，数字不可相加。均为零失败、零错误、零跳过。

| 运行 | 实际结果 | 证明范围 |
|---|---:|---|
| 定向 8 套件 | 33 项通过 | 时钟、目录、角色、候选、凭据代数与模块边界 |
| 默认完整 `clean verify` | 796 项／119 套件通过 | Linux Java 21；实际编译 252 份产品、128 份测试源码；含强制安全存储 20 项 |
| 兼容配置 `clean verify -DskipITs` | 851 项／124 套件通过 | 实际编译 252 份产品、169 份测试源码；41 份集成源码编译，含安全存储 20 项；该轮不执行实库 IT |
| `AuthorizationTemporalClockPostgresIT` | 17 项通过 | 真实 HTTPS 登录、持久 Session、生产授权查询、真实 PostgreSQL 事务与守卫等待；13 个枚举调用、4 个普通用例 |

真实 HTTPS 仅用于登录并取得实际持久 Session 主体；登录后，用例在进程内调用生产授权策略及带守卫的 PostgreSQL 事务。17 项不是 Finding 业务命令的 HTTPS 端点调用，也不启动完整 Finding／Flowable 应用。

17 项实际运行覆盖：成员／组织／岗位／用户组授权截止；组织／岗位／用户组成员关系截止；目标组织成员资格及部门树种子截止；直接授权和继承成员关系开始生效；不重叠的成员关系与授权区间禁止跨查询拼接权限；独立 RR 保留原时间而新快照／RC 看到到期；命令先持锁和安全写事务先持锁两个顺序。

自然开始／到期场景先提交有限时间区间，持锁者仅持守卫，不修改角色 active、身份状态或 epoch 来制造拒绝。真实 `pg_locks`／阻塞 PID 确认等待；数据库截止、事务开始、守卫后时间与授权时间一起断言。查询观察使用实际连接，检查时钟、目录和角色等查询与外层事务使用相同 backend PID、同一评估时间；不替换 SQL、绑定或结果行。非重叠区间用例刻意使两个查询跨过边界，仍必须拒绝。

下游提交证据使用本例实际登录 Session 上独占的合成属性。允许路径检查提交后有一条标记，拒绝路径检查零条；没有把 Java 计数器当作 PostgreSQL commit。既有业务／事件／引擎表的可读租户行数前后检查只是有限差分，不证明完整 Finding／Flowable 写事务或完整引擎回滚。

## 源码、运行输入与保留失败

冻结 11 份源码的聚合 SHA256 为 `36eda32e7588e02fc27f84b3865ea58528f1aa2999402e212df3183f4160bcfb`；458 项构建输入清单 SHA256 为 `5a401a367ba4c0e4c452f5d06e560acb37ee44be3a174697f0732c9bc1edd211`。集成源码 SHA256 为 `600e2016a527712082dc240c8a07a468f0e0a987acec7af20c595b4d3ca5ad29`，实际编译主 class SHA256 为 `0b2f9e9e4fac8e73be5f51c1fe083979d2ccb518e8bbe9ae00b872cb9fa54425`、major 65。默认／兼容配置的实际 XML、来源源码、class 与 Java 21 运行来源绑定分别保留，不用旧 target 代替当前源码。

实际 IT 的安全摘要确认：194 项 classpath；866 个 class 加 24 份资源共 890 项运行输入前后匹配；1,360 项来源输入未变；唯一一致的 helper 成功退出 0；17／0／0／0；数据库容器资源前后相同。1,360 项执行来源清单和 458 项构建输入清单口径不同，不互相替代。

保留的失败没有抹去：早期定向测试 33 项中 9 项失败，原因是 Mock JDBC 时间绑定编码断言把驱动字符串当作 OffsetDateTime；只修正测试解析，仍保留时间等价、绑定顺序／次数和 SQL cast 断言。首次实际 17 项执行曾全部出现错误，原因是启动包只复制 866 个 class，缺少 24 份运行资源；补齐封存资源后产品与测试源码均未改变，才取得本次 17 项实际通过结果。

预检工具还暴露两处基础设施问题：PostgreSQL `inet::text` 包含 `/32`，不能与资源证明中的裸 IP 直接相等；独立副本改为 `host(inet_server_addr())` 后仍保持精确 IP 相等检查。Flyway 12.4 的独立 validate 会拒绝预期唯一待执行版本；另一个独立副本先核验完整迁移状态及历史校验和，再仅接受当前阶段唯一的 `RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED` 结果，保持空忽略模式和迁移后严格校验。原封存工具及失败证据保留。副本曾因缺少本地 evidence 父目录在目标调用前退出，单独记录后由主协调安排首次实际执行；不冒充一次数据库失败或自动重试。这些修正仅用于验收基础设施，不是产品权限、迁移历史或 schema 变更。

R3 实际只读预检最终得到 `LOCAL_INPUTS VERIFIED` → `BEFORE VERIFIED 11` → `VALIDATE_INFO VERIFIED 11` → `READ_ONLY_COMPLETE NO_MIGRATION 11`，之后才独立安排窄实库验收。

## 证据索引与范围限制

以下是本地保留的脱敏摘要入口；原始私有 XML、日志、凭据、Cookie、Session ID、账号值和 TLS 私钥不进入本报告。

| 证据 | 本地路径 |
|---|---|
| 定向结果及原 33／9 失败 | `/tmp/msf-p0-authorization-clock-linux-20261103/attempt2/targeted-verification-summary.json`；原失败在相邻 `attempt1` |
| 完整默认与兼容配置 | `/tmp/msf-p0-authorization-clock-linux-20261103/attempt3/default-verification-summary.json`；同目录 `profile-verification-summary.json` |
| 原冻结来源与源码/class 绑定 | `/tmp/msf-p0-authorization-clock-linux-20261103/attempt3/handoff-summary.json`（当时实际 IT 尚未运行，不能把该历史字段当作最终状态） |
| 最终只读预检 | `/tmp/msf-reviewed-phase-migration-runner-expected-pending-r3-20261104/evidence/20261103T171817Z-ced6329993034246adb500e239976d78/safe-console.json` |
| 最终 17 项实际验收 | `/private/tmp/msf-p0-clock-actual-it-resource-r3-20261104/evidence/20261103T175138Z-85fe5e2be7ac4b03980965a717385d62/verified-acceptance-summary.json` |
| 独立实际完成证据复核（限定范围 PASS） | `/tmp/msf-p0-clock-actual17-completion-independent-review-20261104.md` |

目标保持既有独占 V14／11 条历史，未应用 V15+、未创建表／角色、未授新权限，也未操作真人账号或密码。本次按既有授权执行并清理 owned 合成身份、目录、授权与 Session 测试数据，使用已有运行／迁移角色和权限；不是零数据写入验收。真实 HTTPS 登录仅证明本窄配置的测试入口，不扩大为整个登录产品、G008／G013、Finding／Flowable 业务、生产容量、故障恢复、NAS 网络或安装发布验收。独立实际证据复核已完成，结论为上述限定范围 PASS；后续 Git／PR 批次由主协调处理，本报告不宣称交付或更广范围验收已完成。
