# G007 身份生命周期核心源码检查点 — 2026-11-03

本批交付后端核心源码及本地构建证据。它不代表 G007 完成，不包含新 HTTP 接口、过滤器或桌面接入、真实投递、V19 部署或 PostgreSQL 验收。

## 已实现的核心行为

- 邀请签发、撤销、显式重发，以及重置请求、单次消费和已提交命令回执重放。32 字节随机能力令牌仅暂存在有界内存；数据库保存摘要，命令正文指纹使用配置的专用保留 HMAC 密钥。缺失密钥或投递配置时拒绝接纳，没有临时密钥或投递成功兜底。
- 新账号消费由同一受管理 RW/READ COMMITTED 事务编排 account、HUMAN principal、PASSWORD credential、精确联系人、普通租户成员、挑战状态、回执和成功审计。已有账号加入要求精确且新鲜的目标账号 Session；不按邮箱合并账号、不授予角色、不允许租户管理员重置全局密码。实库原子性尚待验证。
- 事务适配器固定 DataSource、物理连接、ConnectionHolder、线程、事务模式、同步和生命周期；先取得全局排他锁及声明过的排序租户锁，完成来源 Session 验证后单向切换目标租户。现有 Session 查询不会在切换后重新设置来源租户。
- 先证明令牌持有与当前适用权限，再检查精确已提交回执；未命中回执才检查未使用、有效期、发行凭据代数和不存在条件。自身成功写入、原令牌到期及后续改密不会使精确重试重复产生效果；账号、成员、联系人、发行人或应用权限的实际撤销仍拒绝确认。
- 新授权范围仅为 `IDENTITY_TENANT`、空 definition、无 scope_org，以及两条精确邀请权限元组；不把 NULL 或既有 Finding scope 扩展为租户全权。发行人代数是不可变来源信息，消费时读取当前权限。新授权有效期使用锁后的 `statement_timestamp()`。
- 来源/全局接纳先于解析；账号或选择器限额使匿名重置请求返回通用成功。全局内存队列容量先于账号查询和选择器分支；持久限流表容量也先于 selector bucket 查询，表满时已有未达限、已达限和缺失 bucket 均返回 CAPACITY，且零 UPDATE/INSERT。
- 投递桥只在确认提交后移交内存令牌。持久尝试含不可变实例 ID、截止时间和追加式状态；超时观察仅将未完成尝试标为 UNKNOWN，不宣称节点死亡、不恢复或自动重发令牌，迟到响应不能升级终态 UNKNOWN。默认提供方仍拒绝投递。
- 独立失败审计保留真实、可为空的来源。内部诊断使用固定阶段/失败类别、有界计数和每分钟最多八条日志，仅接受 UUID 引用；不记录异常消息、堆栈、原因、SQL、绑定值、密码、摘要或收件地址。

模块 DAG 仅增加已批准的 `Support services → Identity and authentication`，并更新对应固定期望集合；Identity 的 allowedDependencies 仍为空，其余边界和旧登录规范化语义保持不变。

## 冻结与执行证据

最终产品源码冻结清单涵盖 48 份所属源码/元数据，复制前后及捕获文件哈希一致：

`aaf32acb0ec8d2fb3e833d8cceeb04560438db16a02cd008e6d3c3f3e20a75ad`

构建另行捕获 448 份实际源码、资源、契约和构建输入，未复制旧 target/class 文件。环境为固定缓存的 Linux arm64 Maven 3.9.11 / Temurin 21.0.9+10；镜像 ID 为 `sha256:3ead0ff36a4b796440e451013a4ce803dbd02f07b6c5b634cc2ad67927dfcc10`。容器使用非 root 用户、只读依赖缓存、离线 Maven、无网络，并强制 `software-factory.test.require-secure-storage=true`。所有实际 XML 均绑定到捕获源码及本次 Java 21 编译输出。

| 执行 | 实际测试 / 套件 | 失败 / 错误 / 跳过 | 结果 |
|---|---:|---:|---|
| 定向原 38 套件 + 隐私、诊断、实际 DAL 三套件 | 312 / 41 | 0 / 0 / 0 | 通过 |
| 完整默认 `clean verify`，无单元测试过滤或跳过 | 751 / 115 | 0 / 0 / 0 | Maven exit 0 |
| 完整 `-Plan-postgres-compatibility -DskipITs clean verify` | 806 / 120 | 0 / 0 / 0 | Maven exit 0 |

这些运行相互重叠，测试数量不相加。默认构建实际重编译 247 份 main、124 份 test Java；兼容配置实际重编译 247 份 main、164 份 test Java，包含全部 40 份集成测试源码。兼容配置多执行的 55 项是五个目标/权限/策略校验单元套件；Failsafe 明确跳过 IT，真实 IT XML 数为零。编译成功不等于集成测试执行。日志保留了非致命 Jansi 原生库加载及既有编译警告。

原始本机证据分别保留在以下目录；包含命令、镜像信息、源清单、完整日志、XML、编译类副本及绑定摘要：

- 最终定向及完整构建：`/tmp/msf-g007-capacityfix-linux-20261103/`，摘要为 `targeted-verification-summary.json`、`verify-verification-summary.json`、`profile-verification-summary.json`。
- 最终源码冻结：`/tmp/msf-g007-capacityfix-source-freeze3-20261103/freeze-manifest.json`。本报告在验证完成后新增，不属于该产品源码冻结；产品源码哈希随后再次核对未变。

## 保留的失败与修复

- 上一冻结源的 40 套件运行：308 项，0 失败、1 错误、0 跳过，保留于 `/tmp/msf-g007-reviewfix-linux-20261103/`。错误来自新提供方测试使用 `when(...).thenThrow(...)` 重新设置已有阻塞 Answer，设置桩时即触发等待；改为 `doThrow(...).when(...)`，未放宽断言。
- 容量顺序缺陷先在实际 `JooqIdentityLifecycleStore` 经 mock JDBC 执行的四项回归中复现：4 失败、0 错误、0 跳过，保留于 `/tmp/msf-g007-rate-red-linux-20261103/`。其中表满的已有未达限 bucket 返回 ADMITTED。最小修复将全局容量计数移到 bucket 查询之前；最终四项均通过。该证据证明实际 DAL 分支与 SQL 调用顺序，不是 PostgreSQL 引擎或并发证明。

## 未完成的资源与验收门禁

V19 **UNAPPLIED**。本批没有运行真实数据库、迁移、授权、账号、密钥或投递资源变更。验证过程未操作 Git；源码发布另行记录。身份表/回执/审计的真实原子性、RLS 与最小权限、竞争提交顺序、重启/跨节点及实际投递仍待单独验收；新 HTTP/CSRF/失效 Cookie 过滤路径、桌面交互和完整登录闭环也未在本批接入。

限流 bucket 按既定计划保留，不删除历史；达到全局容量后统一拒绝接纳。这是明确的运维维护门禁，本批未增加删除任务或通用维护框架。真实密钥保留策略、现有人类账号经 G008 验证的联系人接入，以及实际提供方配置和接收方收信证明仍然缺失。单元假提供方、编译完成或提供方 ACCEPTED 均不能替代真实收件验收。
