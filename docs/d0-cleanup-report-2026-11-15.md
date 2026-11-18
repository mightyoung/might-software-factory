# AI SLOP CLEANUP REPORT — D0

日期：2026-11-15。模式：`ai-slop-cleaner` 标准模式，作为当前 Ralph 执行中的有界整理步骤；不是 `--review`。本报告记录执行结果，最终整理后回归由 root/test agent 统一运行。

## Scope

仅检查本轮 `integration/d0-convergence` 相对 `f2e2b09` 的已修改及新增 D0 文件：`tools/d0`、V26、两份 runtime operator SQL、D0 权限与目标保护测试、受影响 PostgreSQL IT、bootstrap/验证/权限文档。既有业务主代码不在整理范围。检查时涉及的修改是 D0 环境隔离、授权清单、证据采集和断言适配；不增加依赖或通用框架。

## Behavior Lock 与 Cleanup Plan

计划由 root 写入 [D0 验证记录](d0-closure-verification-2026-11-15.md)“变更整理计划”，独立 architect 已审阅通过，整理代理执行本报告，root 负责最终回归与阶段结论。整理前依据已读取的锁定结果：核心 25 类实库 258 项、P0 21 项、Linux 后端 2530 项零跳过、前端 419 项、mock 浏览器 20 项及真实业务/权限浏览器通过；bootstrap 18 项拒绝/目标检查与实际多授权拒绝亦有独立证据。这些是 root 提供并归档的整理前结果，不是本代理重新执行的测试。

执行顺序：失败/兼容路径分类 → 删除死变量 → 检查重复和命名 → 检查相应测试与证据是否保留。安全 SQL、迁移校验和、目标保护、权限集合及业务断言保持原语义。

## Fallback Findings

| 路径 | 分类与理由 | 处理 |
|---|---|---|
| migrate 的 V23 operator 分段及可重跑属主恢复 | Grounded compatibility：历史 FORCE RLS 前提明确；普通 migrator 从未临时提升；失败继续抛出 | 保留，不折叠为单账号或自动 repair |
| framework 已有 schema 校验/缺失时初始化 | Grounded compatibility：固定版本引擎管理自身表；部分 Session schema 拒绝；重跑保留已有内容 | 保留，不改为忽略 schema 错误 |
| seed 已存在租户验证、重复执行 | Fail-safe：比对专有标记、身份、口令与精确角色授权；漂移即拒绝，不默默重授或重置 | 保留；对应离线 target guard 与实库重跑/漂移证据 |
| FixturePostgresTarget 及 d0-application profile | Grounded compatibility：公共前置验证取代硬编码旧库地址；旧 profile 权限含义保留，D0 有独立库名及 ACK | 保留；目标、角色、ACK 负例不删除 |
| bootstrap 异常摘要与浏览器失败处理 | Fail-safe：前者返回非零并保留 SQLState，不打印敏感 SQL；后者标 FAIL、保留已完成步骤并返回非零 | 保留，不将异常当成功或默认数据 |
| 浏览器 ignoreHTTPSErrors | Grounded test boundary：仅严格校验的自有 HTTPS loopback 目标、私有测试证书；不改变应用 TLS 配置 | 保留；真实浏览器验证与生产 TLS 另列 |
| V26 on conflict do nothing | Grounded migration compatibility：先拒绝不兼容权限元组，兼容身份及授权不替换 | 保留；三态迁移验证独立记录 |
| 整表/额外列权限或匿名角色来源拒绝 | Fail-safe：operator 清单和最终 effective ACL 双重核验；继承/PUBLIC 扩大时回滚 | 保留；不以扩权消除测试失败 |

未发现需删除的 masking fallback slop；无吞错成功、合成数据替代实库或未经测试的备用执行路径。没有需要另开架构决策的整理发现。

## Passes Completed

1. **Dead code**：删除 `d0-runtime-grants.sql` 授权应用块中未使用的 `col text` 声明。该块实际使用 `spec`、`privilege`、`columns_sql`、`routine`；末尾 effective ACL 块仍保留正在使用的 `col record`。没有删除执行语句。
2. **Duplication**：保留 legacy 与新迁移两个独立 ACL 清单及新旧 runtime profile。它们分属已审查的数据库能力边界，合并会扩大本次变更；没有增加 wrapper 或抽象层。清单来源断言使用共同事实但验证目的不同，不删减负例。
3. **Naming/error handling**：未发现必须更名的本轮符号或调试残留。独立工具入口的短类名与命令文档一致，统一重命名没有实际收益。保留具体失败消息、SQLState 和非零退出；未做大面积格式化。
4. **Test reinforcement**：已存在的测试覆盖精确 producer 列、19 列 ingestion 状态、文件锁身份守卫、V23 父锁元数据列、42 表与 32 函数、隔离目标和旧 profile 保留行为。死变量删除无需新增镜像式测试。实际 RLS、升级、竞争、拒绝和浏览器路径继续由现有 D0 证据与最终回归锁定。

## Quality Gates

- 本整理步骤：`git diff --check`、文档相对链接检查、`node --check tools/d0/browser.mjs` 通过；静态确认被删除变量未被引用，effective ACL 的 `col record` 保留。
- 后端构建/实库、前端测试与真实浏览器：本代理按分工未再运行，交由 root/test agent 对整理后快照统一取得 fresh evidence。本报告不提前标最终回归 PASS。
- 新依赖、UI 设计变更：N/A。浏览器文件是验收脚本，未改产品界面或交互样式。

## Changed Files 与 Remaining Risks

- `backend/src/main/resources/db/operator/d0-runtime-grants.sql`：仅删除一个未使用声明，不改变权限、语句顺序或迁移。
- 本报告：记录范围、兼容路径分类、四个整理 pass 及验证边界。

本轮整理未新增风险。root 已读回整理后统一回归：Linux 离线 2533、core 实库 258、相关守卫 61、P0 实库 21 及守卫 42，全无失败/错误/跳过；前端 419 单元、20 模拟浏览器及真实浏览器再次通过，构建、类型、契约与静态检查通过。独立 architect 随后复算当前源码/产物哈希并判定 D0 CLEAR；最终来源与边界见 [D0 验证记录](d0-closure-verification-2026-11-15.md)。既有专业扫描部署、NAS 升级、生产环境验收不因本报告完成。
