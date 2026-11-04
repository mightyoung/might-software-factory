# G014 基础实体与事务守卫源码验证 — 2026-11-04

本批新增限定的基础实体、关系命令及事务守卫源码。独立复审已在明确的源码／夹具边界关闭原五项问题；最新业务／审计组合通过 25 项，启动／模块与受影响接口通过 83 项。**同一最终源码的完整回归尚未取得结果，交付保持面向 `dev` 的草稿 PR，Linux CI 门禁待确认。** 持久 HUMAN 身份、真实 PostgreSQL、完整业务 HTTP 和默认 Modulith 发布闭环仍未验收，不代表完整 G014 或 P0–P2 完成。

## 源码范围

- 六类根实体为 workshop、production_line、process_stage、equipment、work_unit、hazard_source，实现 17 项变更命令；三类关系 line_workshop、stage_equipment、responsibility_binding 实现 6 项 BIND／END。控制器源码共 29 条固定路由，包含六类详情读取。
- OpenAPI 定义 67 条固定路径并提供生成的 TypeScript 类型。路径契约不等于服务端实现：查询、历史、17 个选择器、元数据与导航仍待接线。
- 命令先校验当前身份、动作与实际对象范围，再读取限定范围的历史回执。授权后的精确重放保留原结果，跳过旧 CAS、旧来源资格和 NEW 锁计划，不新增业务、修订、审计、回执或发布写入；仍执行固定约束确认与最终当前授权。
- NEW 命令使用同连接 jOOQ 和固定类型投影；先取得 SQL 所有者提供的自然键、对象／修订／命令键，再按固定实体种类及无符号 UUID 顺序锁定完整父对象／目标集合。业务、修订、审计、完整回执和实际发布写入应全部处于同一 STAGING 边界。
- V21 候选包含 19 张表、40 项延迟约束及 46 个函数。19 个写入路径核验本连接已持有的统一编码锁，再非阻塞固化事务锁；缺失键立即拒绝。源检查和参考编码向量没有执行 PostgreSQL 函数或触发器。
- 专用 G014 管理器保留永久事务失效、唯一最终授权切点及私有提交屏障。有限的二参数 `setTimestamp`／`setObject` Timestamp 路径复制驱动值并保存不可变 `Instant` 观察，其他 Date 或重载仍被拒绝；最早同步器在挂起前拒绝，发布别名窗口校验其身份与顺序。未知提交／回滚结果保留不可复用连接；容量、健康检查及恢复仍待完成。

安全时钟沿实际 `ManagedSessionSensitiveSecurityTransaction` 路径读取同连接的 PostgreSQL `statement_timestamp()`，没有新增 Java 时钟或截断最终安全切点。微秒时间从当前授权切点传入结果、业务与事件；异常服务器精度拒绝并回滚。首次响应／持久回执／重放的实库等值证明仍开放。

## 框架兼容与固定 SQL

各所属模块导出实际查询复用的有限 SQL，根清单在构造时合并，并拒绝参数数、阶段、效果或确认类型冲突。清单仅允许准确注册的 SQL，不接受前缀匹配或请求期注册。

V5 使用 legacy `event_publication` 结构，而固定的 Modulith 2.1.1 默认选择 V2。本批显式配置 `spring.modulith.events.jdbc.use-legacy-structure: true`，并核验实际 legacy 仓储类型、PostgreSQL 设置及有限 schema 形式。早期 SDK 子上下文只使用模拟元数据；后续真实 Managed 业务夹具执行实际默认 legacy 仓储／JdbcTemplate 的 INSERT 绑定路径，但底层驱动仍为夹具，没有 PostgreSQL 存储结果。

`spring-modulith-events-core` 与 `spring-modulith-events-jdbc` 仅从既有同版本运行依赖提升到编译范围。前后 191 个物理运行 JAR 的集合及 SHA-256 一致，没有新增物理库、版本或下载；这不声称类路径顺序相同。全局数据源与事务管理器未被该源码清单替换。

根配置现显式构造真实命令／读取服务及各自私有事务图，模块通过窄的固定 SQL 描述接口接线。实际非延迟 H2 启动保留一个全局 DataSource、事务管理器与 DSL，以及 31 个扫描仓储。G014 在 H2 或缺少所需密钥时按真实依赖边界拒绝，不生成密钥、不伪造 OFF 或成功。元数据失败测试证明关闭尝试与 suppressed 异常保留，不能证明连接无泄漏或干净归还池。

## 已执行验证

测试集合重叠，不可相加。本文整理保留的运行与独立审查记录，没有重新构建或连接数据库。

| 冻结验证 | 实际结果 | 证据边界 |
|---|---:|---|
| 历史联合 R7c 定向验证 | 105 项／11 套件，F0／E0／S0 | 81 项业务夹具、11 项清单／SDK 夹具、9 项 SQL 源码契约、4 项 OpenAPI 契约；后续审查仍发现组合缺陷，不能作为当前闭环证据 |
| gate 所有的固定约束 flush | 64 项／5 套件，F0／E0／S0 | 实际 Managed 回调正常关闭后才 flush，保留唯一最终切点与永久失效；限定支持层审查 APPROVE |
| 实际 JdbcTemplate Timestamp 绑定修复 | 70 项／5 套件，F0／E0／S0 | 精确二参数 setter、防御复制及拒绝其他重载；限定支持层审查 APPROVE |
| 最新真实 Managed 业务／审计组合 | **25 项／2 套件，F0／E0／S0** | 23 项业务：workshop NEW／重放、三类关系 NEW／重放、五类根归档的成功／下游不空／状态错误；另有 2 项审计时间绑定测试。使用实际默认 legacy 仓储和 JdbcTemplate，底层驱动与身份／授权为声明夹具 |
| 启动／模块及受影响接口 | **83 项／11 套件，F0／E0／S0** | 真实非延迟 Boot／H2、默认 legacy 仓储与 listener；含 Cookie、缺密钥接口及四个受影响既有 HTTP 套件。限定启动架构审查 CLEAR |

最新 25 项组合使用 Linux Java 21，绑定 800 项源码／构建输入，清洁编译 351 项主源码和 186 项测试源码；1153 个 class 均为 major 65，并从保留的编译产物副本核对 SourceFile、源码／class 哈希、25 项运行资源和实际 XML 类路径。83 项启动验证使用另一份 800 输入冻结切片，产物为 1152 个 class／25 项资源，未包含后续时间类型修正；两个切片不可互相替代。它们没有执行 PostgreSQL IT、V21 schema 或持久 HUMAN 验收。

定向目标使用 Maven 离线 `clean test`、明确测试集合和既有 Mockito 5.20.0 agent；容器取消三个 Java 选项环境变量，使用只读本地依赖缓存和无网络环境。精确参数保留于各运行入口的 `target-command.json`／`target.sh`。本地完整默认回归因 Docker daemon 无响应，在 Java 启动前停止，执行 0 项测试；兼容配置未启动。草稿 PR 的 Linux CI 将执行普通默认 `clean verify` 及 `lan-postgres-compatibility`／`skipITs` 验证，结果待确认；兼容配置即使通过也不计为实库执行。

## 失败与独立审查

- 时间参数与事务挂起先由失败回归复现；修复进一步提前到 Spring 挂起既有同步作用域之前拒绝。原失败及中间失败均保留。
- 原 6 项清单测试没有覆盖实际 Managed 事务的 `statement_timestamp()`。新增真实事务守卫夹具先复现 1 项失败，再由所属模块复用准确时钟 SQL 并加入两个校验阶段。中间 Mockito 夹具失败也保留。
- 联合 R7 的前两次编译在新增测试夹具的 jOOQ `Record` API 使用处失败，均执行 0 项测试。按固定 3.21.9 接口核验后，仅将夹具改为 `set(Field<T>, T)`；三次尝试的业务生产源码保持一致。
- 时间／挂起、固定清单及 Managed 时钟增量均获得限定范围的独立代码审查 APPROVE。统一锁 SQL 与业务 R7 获得独立架构 CLEAR；该结论只覆盖冻结源码，没有执行或批准 PostgreSQL DDL、权限或实库验收。

完整业务审查曾提出 2 项 HIGH、3 项 MEDIUM。独立闭环复审为 **APPROVE，五项均在下述源码／夹具边界关闭**；这不代替完整默认／兼容回归或真实服务验收：

| 审查问题 | 修正与限定证据 |
|---|---|
| HIGH：回调内部 flush 阻断真实 Managed 关闭检查 | 四处内部 flush 移至 gate 所有的固定控制入口，在 STAGING 回调正常关闭后、最终校验前执行；真实 Managed NEW／重放组合验证正常提交，未放宽 post-ack SQL 策略 |
| HIGH：控制器缺少服务 bean 导致启动失败 | 显式真实根图、实际非延迟启动及依赖拒绝路径进入 83 项验证 |
| MEDIUM：归档缺少最终状态／下游谓词 | 五类根在同一 t1 检查实际 ARCHIVED 版本及当前下游空集；成功、下游阻断及错误状态均进入 23 项业务组合 |
| MEDIUM：数据库异常丢失内部原因 | 保留原异常原因及受限 SQLSTATE／类别访问器，公开错误体继续保持通用；没有新增内部日志调用，生产日志尚未使用这些访问器 |
| MEDIUM：根清单访问模块内部服务 | 改为模块导出的窄 SQL 描述接口，既有 ModuleArchitectureTest 在启动切片通过 |

真实组合还暴露了 jOOQ 默认时间绑定与严格观察值的差异。业务与审计固定使用 `withBindOffsetDateTimeType(true)`，保留准确 OffsetDateTime 绑定和严格参数比对，没有扩大匹配规则。普通 H2 发布测试只观察默认框架总完成数增加及 listener 代理；它没有证明精确 listener ID、执行线程、PostgreSQL sealed 命令同连接或提交后独立完成事务。

## 固定证据

下列入口仅供本地审计核对；私有日志、完整 XML、凭据、Cookie、密钥及环境文件不进入发布清单。

| 项目 | SHA-256／本地入口 |
|---|---|
| 联合 R7c 完成包 | `5920f6dd63f266134a770d3e76442619b2777580e441e6b3d80faa24d839b829`；`/tmp/msf-g014-business-r7-combined-linux-20261104/final-packet.json` |
| gate flush 完成包 | `8fc7755b660e315d1bccd660a36cb082f8dd4d0735542149da7a4bdd13681eb7`；`/tmp/msf-g014-gate-flush-linux-20261104/final-packet.json` |
| Object Timestamp 完成包 | `429da2247f072a848f4e6bc082e033ed94bcecec1e9c58d9f41aedae04b73b97`；`/tmp/msf-g014-object-timestamp-linux-20261104/final-packet.json` |
| 最新 25 项组合摘要 | `778b1fd3c39bdf572d7e11ded76430b264c5838b5a72f319152c8d4834ad6215`；`/tmp/msf-g014-domain-typed-linux-20261104/focused/target-verification-summary.json` |
| 最新 800 输入清单 | `52873066c5131a93425e25e815e3694b57c5a6c6ea47736a97c2260546c4815c`；同入口 `source-manifest.json` |
| 启动／模块完成包 | `de09f27810cc06b72365e4630e3c8aa2aacf275428dac471290b7f58c642644e`；`/tmp/msf-g014-runtime-graph-linux-20261104/final-packet.json` |
| 启动独立架构审查 | `e4b2c2592babf509db89fcc68613b6c7ec1b13ea7bc0d22d1d060ad51cf939be`；`/tmp/msf-g014-runtime-graph-independent-architecture-review-20261104/README.md` |
| 五项独立闭环复审 | `bd626aa3e78ebe5e36a8bf98126ebfd424c6ebe1a138b1931554ba93807bdeaa`；`/tmp/msf-g014-business-r8e-audit-closure-independent-code-review-20261104.md` |
| 待全回归的 800 输入清单 | `ff69806b4fee870c5ef6e389be505d405e5f60d9c9962a869b2d5df1a5c808a3`；`/tmp/msf-g014-typed-full-linux-20261104/attempt1/source-manifest.json`；已冻结不代表已执行 |
| V21 候选源码 | `1c68e165319f3a46ff8b42625db25c93890f5fa7e1e329fa2ddf1446203ad694` |
| 统一锁独立架构审查 | `5fd91e3432db0d0fca87ea0922fcfd09cad8d8187e696450bf0f08ae58723c14`；`/tmp/msf-g014-canonical-lock-independent-architecture-review-20261104/README.md` |

## 待补验证与交付状态

以下项目尚无最终结果，后续完成后再更新本节，不沿用旧基线或定向结果填充：

- 冷启动 jOOQ 测试隔离及普通复用 fork 配置的最终整批结果。
- 同一最终源码的完整默认后端与 `lan-postgres-compatibility` Linux CI 验证；当前没有通过计数，兼容配置使用 `skipITs` 时只编译集成源码。
- 草稿 PR 推送与 CI 结果核验；源码复审通过不等于完整回归通过。

V21 尚未应用或授予函数执行权限，V15–V17 的新增目标权限方案仍待批准。本地仅整合既有 PR #47 的四项 Cookie 源码／说明文件；没有远端合并、部署、新增目标角色、密钥或服务。不补造历史 V9 源码，不以 baseline、repair 或乱序迁移绕过原门禁。

真实非特权角色下的 V21 解析／DDL、19 路径与锁编码等值、锁等待／撤权／回滚竞争、全部 40 项约束、NEW PostgreSQL DML／修订／审计／回执／发布流水，以及持久 HUMAN 身份、业务 HTTP 和默认 Modulith 同连接 INSERT／精确 listener／异步线程／提交后独立完成事务，均须另行验收。驱动夹具、H2 启动或总完成数不能替代这些证据。

根配置的实际 PostgreSQL 运行与真实密钥／HUMAN 依赖、其余查询／历史／选择器／元数据／导航、完整风险评估与措施、检查、任务／通知，以及其余 P0–P2 工作仍开放。
