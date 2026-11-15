# D0 补齐验收记录

状态：**D0 CLEAR，允许冻结并进入 D1**。最终回归全部通过，独立 architect 已重新计算源码清单及 jar 哈希、读取 XML 和浏览器证据，确认此前问题全部闭合。

## 范围和候选

- 分支：`integration/d0-convergence`，起点 `f2e2b09d74560cf0da67ab6d390cb3cf76ef125f`，另有本轮未提交修复。测试源快照另存逐文件 SHA-256，不能把起点提交号当作最终候选的完整标识。
- 本轮只操作本机 Docker PostgreSQL 18.6 新库：`msf_d0_core_test`、`msf_d0_browser_test`、`msf_d0_probe_test`、`msf_d0_upgrade_test`。没有改旧开发库、旧验收库或 NAS。
- 数据库和 public schema 的 owner 是 provisioning 管理员；migrator/runtime 分离，均无 SUPERUSER、BYPASSRLS、CREATEDB、CREATEROLE、REPLICATION、INHERIT 或角色成员关系。runtime 无 DDL/TEMP。
- D0 证明两条实现线的可重复集成基线，不表示软件工厂、D1、借还应用或生产扫描部署完成。

## 补齐内容

1. V26 补 canonical `finding.read`，缺失时创建、兼容时保留 ID/授权，不兼容时拒绝。
2. `tools/d0` 提供独立迁移、固定版本框架初始化、合成种子、显式流程部署及真实浏览器检查。V23 保留单独 operator 步骤，随后归还函数属主。没有 repair 历史、禁 RLS/触发器或启动时自动建表。
3. 运行权限拆成 V2–V18.3/framework 基线和 V19–V25 增量。新增迁移实际为 **42 张表**；原记录的 41 不正确。列权限覆盖实际 producer SQL，脚本同时验证有效权限。
4. 实库测试移除旧库地址硬编码，使用明确 target、allowlist、两个非特权账号及具体 ACK。增加 `d0-application` 完整 ACL 档，保留旧权限档含义。Flowable 按当前禁用 IDM/event registry 的 **32 张表**验证。
5. 修正合并后过期的测试假设：CTE SQL 统计、历史上传时间字段、FORCE RLS 拒绝优先序、不可变字段触发器探针的执行身份和导航测试装配；保留运行账号拒绝及业务断言。

## 当前证据

| 检查 | 结果 | 证据目录或命令 |
|---|---|---|
| Linux 后端离线全量 | 最终 2533 测试，0 失败/错误/跳过；jar 构建成功 | `/private/tmp/msf-d0-final-verification/summary.json`；`mvn -o -Dsoftware-factory.test.require-secure-storage=true clean verify` |
| Linux native | protocol、12 项加密失败注入、negative argv、只读容器 crypto smoke 通过 | 同目录 `native-build.log`、`crypto-smoke.log` |
| 前端单元/构建 | 419 通过；类型、构建、体积和 11 份契约类型一致性通过 | `npm test`、`npm run build`、`npm run check:bundle`、`node tools/api-types/generate.mjs --check` |
| 模拟服务浏览器回归 | 20 通过，明确不是实库验收 | `/private/tmp/msf-d0-closure/frontend-e2e.log` |
| 空库与重跑 | 真实 Flyway 至 V26；framework/seed 重跑通过；流程部署重跑验证一致 | `/private/tmp/msf-d0-closure/msf_d0_browser_test-*.log` |
| 有数据升级 | 真实 V18.3 → V26；22 表各 1 条合成记录逐表 hash 不变；18 条旧 Flyway 记录完全保留；V26 三态通过 | `/private/tmp/msf-d0-closure/upgrade-summary.json` |
| P0 实库 | 最终同源码复跑 21 项 IT + 42 项目标/角色检查，全部通过、无跳过 | `/private/tmp/msf-d0-final-probe/final-probe-summary.json` |
| 业务/身份实库 | 最终单次完整运行 25 类、258 项 IT 全部通过，无失败/跳过；完整 ACL、metadata 正例、核心语义列 42501 及保留定义 23514；另有 61 项守卫检查通过 | `/private/tmp/msf-d0-final-verification/summary.json` |
| 真实浏览器 | 换用最终 jar 后再次全程通过：HTTPS 登录与刷新、上报、分角色确认派单、缺证据禁止提交、撤权后直接 API 403、重新授权、最后管理员保护 | `/private/tmp/msf-d0-closure/live-browser.log` |
| 整理后回归 | 全部通过；仅删除一个未使用 SQL 变量 | [整理报告](d0-cleanup-report-2026-11-15.md) |

去敏汇总见 [D0 证据 JSON](evidence/d0-2026-11-15.json)。最终后端/P0 快照包含 1147 文件，清单 SHA-256 为 `9d387e5656cd72ac5d50bf010674134bc6f662088476e36ac4985b9dfa4b3f78`；运行后全部源文件哈希不变，root 再次逐文件对照当前工作区也一致。最终 jar SHA-256 为 `ab403d0186cf01dac1725953fba12be518f092f1c84375a6ec6d9a9c711df2af`，真实浏览器使用此产物。不同套件的重叠守卫计数分别报告，不累加成独立用例总数。

原 D0 文档的“2527 通过、39 跳过”表述不准确：旧 XML 总计 2527，其中实际执行 2488，跳过 39。跳过的是 macOS 不支持的安全文件系统分支，不能归为真实数据库测试。上表 Linux 强制安全存储验证消除了这些跳过。该离线计数也不能代替 Failsafe 实库结果。

## 验收边界

- 升级样本中的文件证据是 `QUARANTINED/UNVERIFIED` 元数据；不证明物理字节、可信内容回执或封存提交升级。可信终结链由本轮独立实库 finalization/整改测试验证，不能合称为旧生产数据升级。
- 浏览器采用真实 API 和 runtime 数据源；没有用 mock 响应替换后端。可信文件上传仍须独立扫描部署环境，不能拿 native 单测或预构造证据代替生产扫描验收。
- P0 的 Flowable 测试证明独立引擎平稳替换，不证明主机失电恢复。租户审计只读查询身份也有独立权限门禁，不能用 D0 的写账号冒充。
- 原始测试 XML、环境文件及服务日志可能含合成凭据，保存在私有目录，不提交。只提交去敏汇总和源码。

## D0 出口

- [x] 空库迁移、框架及种子可重复执行。
- [x] 有数据 V18.3 升级与 canonical permission 兼容性检查。
- [x] Linux 离线安全存储/native 与前端回归。
- [x] P0 真实数据库事务/锁验证。
- [x] 当前候选业务、身份及完整有效 ACL 实库验收。
- [x] 真实浏览器业务及权限配置全程通过。
- [x] 独立架构审阅问题关闭、变更整理和最终回归。
- [x] D0 结论：CLEAR。下一阶段为 D1 标准工装台账；本结论不表示 D1 已完成。

## 变更整理计划

本轮使用 `ai-slop-cleaner` 标准模式，限 `git diff` 及本轮未跟踪的 D0 迁移、授权、工具、测试和文档。root 编写计划，architect 审阅，执行代理检查，root 负责结果与回归。行为锁定证据是上表实库、浏览器、前后端及 bootstrap guard 测试；不清理既有业务实现。

顺序：先检查是否存在吞掉错误、跳过验证或伪装成功的替代路径；再删除明确的死代码/调试残留；再检查重复和命名；最后确认新增拒绝分支都有对应证据。只改明确有收益且不改变安全语义的项，无必要则保留实现。不新增依赖、框架层或兼容分支。

需保留的边界：V23 单独 operator 步骤是既有 FORCE RLS 迁移要求；旧 runtime profile 和固定 32 表配置是显式版本/权限兼容；浏览器仅对任务自有 loopback TLS 证书容错；测试事务 rollback 是负例隔离。以上不得替换为忽略错误、宽授权或生产降级路径。

最终再次执行 Linux 后端验证、受影响实库套件、前端检查与真实浏览器；有新错误继续修复，不凭整理完成宣布 D0 通过。
