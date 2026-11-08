# G008 会话权限核心源码检查点 — 2026-11-04

状态：会话权限核心通过定向 32 项、完整默认 818 项及兼容配置 873 项源码验证。该批次以 G007 HTTP 提交 `92b9088f6a232ed5e12b8e503ff3425a91bdacc4` 为父提交，包含 13 份主 Java 源码、V20 源码和 6 份测试源码。服务没有自动注册为 Bean，没有接入路由、登录或过滤器，也没有开启会话权限强制执行；这不是 G008、完整登录闭环或 P0–P2 的完成声明。

## 本批行为

- 捕获当前请求、实际 HttpSession 与 VerifiedSessionPrincipal，并限制线程和生命周期；捕获事实本身不证明数据库当前权限。独立读快照与写入凭证由所属事务适配器管理，校验同一数据源、物理连接、事务持有者、模式与寿命。
- 提供当前会话注册、安全的本人会话列表、远程撤销与当前已知 lineage 注销核心。使用 PRIMARY_ID 标识会话；不将 bearer SESSION_ID 用作客户端句柄。撤销回执在可变版本、证明状态和到期条件之前检查；已撤销 lineage 保留持久围栏。
- 成功审计加入原写事务。注册、撤销和注销的有限失败事件在写边界返回或抛出之后独立记录一次，不重试原操作。提交结果不明归为 UNKNOWN，失败审计不可用也保持不可确认。
- 只有 CONFIRMED 或明确没有可信 lineage 的 LOCAL_ONLY 才执行本地清理。提交、成功审计、来源捕获依赖失败导致 UNKNOWN 时保留框架会话/cookie 证明；清理失败不抹除已提交围栏。NO_TRUSTED_LINEAGE 的证明与依赖失败分开处理。

失败审计使用契约中明确允许的 ANONYMOUS + REJECTED/UNKNOWN 全空身份字段分支：它记录未证明来源的观察，不声明 HTTP 没有认证，更不从旧 principal 构造 FRESH_SESSION。尚未得到可独立证明的主体、lineage、目标、proof 或 challenge 不会进入失败事件。V20 对此有相同的有限约束。

V20 与已审定 SQL 逐字节一致，SHA256 为 `dafe3dff365e0fd50761af8082a6d6462e7354fdae02d4bea31bb8d7689e7c54`。这里仅新增迁移源码；没有解析执行 PostgreSQL DDL、增加权限、账号或密钥、改变强制执行状态。

## 实际源码验证

冻结 20 份产品/测试文件的清单聚合为 `82504e55ebc842471fb3f6606cc4ba55194bf5fd161e5656923c12301a244c97`。完整构建捕获 476 个输入，其清单 SHA256 为 `9d888fc488c34d5a92d78c330e117978cb562fd971e4dc8f09302d5aadb894f9`。所有轮次均使用同一份固定源，Java 21 缓存 Linux 镜像、非 root、无网络、只读依赖缓存和强制安全存储测试；没有下载依赖。

| 检查 | 测试 / 类 | 失败 / 错误 / 跳过 | 结果 |
|---|---:|---:|---|
| 六个新核心测试类 | 32 / 6 | 0 / 0 / 0 | 通过 |
| 完整默认 clean verify，无测试选择过滤或跳过 | 818 / 124 | 0 / 0 / 0 | Maven exit 0 |
| 完整兼容配置 clean verify，显式 `-DskipITs` | 873 / 129 | 0 / 0 / 0 | Maven exit 0 |

三组计数重叠，不能相加。默认实际重编译 265 份 main、133 份 test Java；兼容配置为 265 份 main、173 份 test Java，其中 40 份 integration-test 源码仅完成编译。实际 Failsafe IT XML 为零，未执行实库验收。两轮完整验证各实际执行安全存储平台与适配器 20 项，无跳过。

完整默认与兼容轮次分别保存 743、897 份编译 class 副本及哈希。各实际执行 XML 均绑定本轮编译 class、冻结 Java 源码、SourceFile 属性、Java 21 major 65 和实际 classpath；两轮完整集合均包含定向六类。定向轮次保存独立 XML 与固定源绑定，不将后续重编译 class 冒充其当时保存的输出。全部 476 个输入在测试前后保持一致。

原始证据保留在本机 `/tmp/msf-g008-session-core-linux-20261104/attempt2/`：`default-result.json`、`verify-verification-summary.json`、`profile-verification-summary.json`、`source-manifest.json`、命令、日志、XML 与编译输出。冻结源码副本位于 `/tmp/msf-g008-session-core-reviewfix-r2-20261104/`。本报告在验证后新增，不包含于 476 个编译输入。

## 审查、修复与保留失败

独立原始源码审查发现两项问题：UNKNOWN 仍清理框架会话、失败审计接口没有实际消费者。本次仅修改 SessionAuthorityService 和其测试，关闭这两项问题，并新增来源捕获 UNAVAILABLE 的 UNKNOWN/no-cleanup 回归。13 项服务测试覆盖成功提交顺序、提交不确定、成功审计失败、独立失败审计失败、缺失 lineage、无认证、清理失败、回执重放、注册拒绝、撤销冲突、提交后有限异常和来源捕获依赖失败；验证一次审计、事务退出后审计和零效果重试。

独立审查者 `session_sensitive_core_source_review` 对 R2 全部 20 文件哈希复核后给出 CLEAR，确认原 HIGH/MEDIUM 关闭、捕获依赖失败不转 LOCAL_ONLY、失败审计来源符合现有契约。该判定来自原生独立审查消息，审查者没有运行测试；协调记录为 `g008-core-r2-source-review-target32-20261104`。它不替代实际环境验收。

首轮定向运行 32 项，0 失败、3 错误、0 跳过。三处错误均为重新设置 Mockito thenAnswer 时先触发原 Answer，空 callback 引发 NPE；测试改为 doAnswer().when，产品服务字节未变、断言未削弱。原始失败完整保留于 `/tmp/msf-g008-session-core-linux-20261104/attempt1/`；随后重新冻结并执行本报告三轮通过结果，没有用旧日志覆盖失败。

限定 20 文件执行 ai-slop-cleaner 检查：先锁定上述回归，再检查失效保护分支、死代码、重复、命名与异常边界。UNKNOWN/LOCAL_ONLY 和有限失败审计为有回归覆盖的保护边界；无必要的删除或格式修改，未增加依赖。空白、冲突标记及 Identity 外向模块 import 检查通过。计划与结果保留为 attempt2 的 `cleaner-plan.json`、`cleaner-report.json`；这是有限静态检查，不是完整安全审计。

## 尚未完成的接线与验收

- 真实登录、新会话策略、CSRF、绝对寿命、凭据版本与所有受保护 HUMAN 消费者接线；HTTP 解析、精确 logout 处理器与外层 SessionRepository 保存失败边界。
- reauth 签发、改联系人及验证消费、真实投递与受控收件；前端会话列表、撤销、密码/联系人交互与生成契约。
- 实际数据库原子性、RLS/最小权限、双方锁等待、回执重放、迟到 Session 保存/ID 轮换、重启与 HTTPS/浏览器验收；旧会话清单和旧写入者排空的完整覆盖证明。
- V18 必须在 V19 之前整合和实际应用，然后才是 V20；不得跳过 V18 后用 outOfOrder、空迁移或 repair 补洞。V20 DDL、grant、保留密钥和 OFF→ENFORCING 激活都需独立审定的实际操作，当前均未执行。

本批没有合并、部署、真实账号注销或改变既有人类身份。发布草稿 PR、线上 CI 和后续实际接线应分别记录，不改变上述开放门禁。
