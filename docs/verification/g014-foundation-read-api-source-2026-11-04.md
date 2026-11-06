# G014 基础台账读取接口源码验证 — 2026-11-04

本批补齐基础台账契约剩余 38 条读取、元数据与选择器路由，基础台账现有 **67 条固定接口均已接线**。完整候选源码的本地 Java 21 定向集成验证通过 **214 项／32 套件，失败、错误、跳过均为 0**；三份独立复审在各自限定范围内均为 APPROVE。2026-11-07 补记：源码提交 `55287df53833f23c3654fc70332c10a69395e712` 的托管 Linux 后端默认 1437 项、兼容配置 1498 项及前端 324 项均通过。真实 PostgreSQL、持久 HUMAN 身份和完整业务 HTTP 链路仍待验收，完整 G014 与 P0–P2 仍未完成。

## 本批范围

67 条接口由前批 29 条命令／详情，加本批 6 条普通查询、3 条关系历史、12 条 schema／definition 和 17 条选择器组成。没有增加六类根实体历史路由。

- 六类根实体共用唯一 schema 发布源。普通查询保留精确十进制比较、限定字段／操作符／排序／投影及范围内 SQL；游标是 58 字节、78 字符的分页锚点，重新核验当前授权、过滤条件与业务版本，不作为权限凭据，不承诺跨页历史快照。
- 三类关系历史先验证当前父对象 READ，再读取包含已结束关系的历史。历史标签来自绑定版本对应的不可变实体修订或保留的目录快照，避免以当前名称替代历史名称。
- 12 条 schema／definition 路由使用固定 READ／CREATE 授权。授权及同一读取快照中的应用版本解析完成后才处理条件 ETag；拒绝或依赖不可用不会返回 304。READ 定义移除编辑、动作与引用控件。
- 17 条选择器包含 6 条自身 CREATE 组织选择、7 条独立 CREATE 锚点／引用 READ 选择、4 条父对象 BIND 选择。固定动作、候选范围与父对象状态分别校验；跨组织合法候选不被错误求交，不增加契约之外的全局唯一占用限制。
- 共享读取快照绑定签发者、线程、ConnectionHolder、物理连接与 RO／RR 回调生命期。捕获后的活动边界破坏仍永久失效并标记原 holder 回滚；关闭后的访问拒绝。查询、历史、定义和选择器共用私有读取图，没有新增全局 DataSource、事务管理器或 DSL。
- 旧 29 条控制器响应统一为 requestId、traceId、schemaVersion 三字段元数据与封闭错误体；命令追踪值只捕获一次。六处 YAML discriminator 仅显式映射既有 READ／CREATE 分支，对应生成 TypeScript 的 12 个标签改为 READ／CREATE，并增加类型对齐夹具。没有新增依赖或迁移。

## 已执行验证

本节集合相互重叠，不能相加。本文核对保留的 32 份最终 XML，以及本批托管 CI 的原始 job 日志和工作流结果；整理本文时没有重新运行构建或访问数据库。

| 冻结验证 | 结果 | 边界 |
|---|---:|---|
| 共享 schema／读取快照 | 9 项，F0／E0／S0 | 620 输入的前期配置变体；不覆盖最终同行接线图 |
| 查询／关系历史 R3 | 33 项／8 套件，F0／E0／S0 | 645 输入；独立复审核对源码、class、资源、JAR 与原始 XML；JDBC 与授权仍为声明夹具 |
| 元数据／选择器／响应契约 | 79 项／10 套件，F0／E0／S0 | 658 输入；独立复审核对编译绑定及原始 XML；前期配置变体 |
| 完整候选的定向集成 | **214 项／32 套件，F0／E0／S0** | 843 输入、375 主源码／204 测试源码清洁编译，1231 个 major-65 class、25 资源、191 物理 JAR；包含实际启动、模块结构、新接口及受影响 Cookie／HTTP／历史 CI 夹具 |
| Node 22 契约对齐 | 包记录生成／一致性检查／tsc 成功，Vitest 1 套件 2 项成功 | 既有缓存执行；未保留原始 Node 命令日志，执行结论是所有者报告的状态包，不能称作独立重读原始输出 |
| 托管 Linux 后端默认配置 | **1437 项，F0／E0／S0，BUILD SUCCESS** | 绑定 `55287df5`；Java 21，安全存储测试必需标记为 true |
| 托管 Linux 后端兼容配置 | **1498 项，F0／E0／S0，BUILD SUCCESS** | 同一提交；`lan-postgres-compatibility`／`skipITs`，安全存储测试必需；Failsafe 集成执行明确跳过 |
| 托管前端完整验证 | **30 套件／324 项通过** | 同一提交，Node 22.22.2；生成类型一致性检查、类型检查及生产构建均通过，原始日志已保留 |

最终定向集成在 macOS Temurin 21.0.13、Maven 3.9.11 下离线运行明确列出的 32 个测试类，目标为 `clean test`，使用既有 Mockito 5.20.0 agent。完整输入清单包含候选树，并不表示执行了完整默认测试集合。启动验证使用实际非延迟 Boot／H2 图，断言新增 38／总计 67 条映射及全局 1 个 DataSource、1 个事务管理器、1 个 DSL 和 31 个扫描仓储。H2 的不支持能力按实际依赖返回 503；没有把它当成 PostgreSQL 业务成功。

最终 packet 与 32 份 XML 的 SHA-256、testsuite 数量及 testcase 数量已在本文整理时逐份核对，汇总一致且无 failure／error／skipped 子项。源码、class 和运行绑定的完整核验范围以对应执行包及独立复审为准，本文没有重新审计全部 class 字节。

同一 843 输入的前一次集成执行为 214 项／4 错误，均是启动测试的监听端口权限错误，不能计作通过。最终尝试获准启动既有 JUnit 临时服务器后通过，没有修改源码、测试、POM 或随机端口策略，也没有设置 server.address；不声称监听地址被限制在回环。更早的范围测试失败仍保留，没有弱化公开响应或 YAML 校验来获得通过。

[PR #49](https://github.com/mightyoung/might-software-factory/pull/49) 的源码提交 `55287df53833f23c3654fc70332c10a69395e712` 已通过[后端工作流 42253793014](https://github.com/mightyoung/might-software-factory/actions/runs/42253793014)和[前端工作流 42253792718](https://github.com/mightyoung/might-software-factory/actions/runs/42253792718)。后端原始 job 日志记录默认 1437 项、兼容配置 1498 项，两次均 F0／E0／S0、BUILD SUCCESS；两条验证命令均要求安全存储测试能力。兼容命令使用 `-DskipITs`，日志明确跳过 Failsafe 集成测试，不能视为实库验收。前端原始日志记录 Node 22.22.2、30 套件／324 项通过，以及生成类型一致性、类型检查和生产构建成功。

托管结论来自工作流状态和原始 job 日志，没有下载并核验 CI class 字节。本地 843 输入／1231 class 的绑定不能外推为托管产物字节证明；本文后续文档提交也不改变上述 CI 所绑定的源码提交身份。早期 Node 所有者状态包仍保留其“无原始日志”的历史边界，本次完整前端 CI 另有原始日志。

[PR #48](https://github.com/mightyoung/might-software-factory/pull/48) 的托管默认 1326 项、兼容配置 1387 项及前端成功，绑定前批生产源码提交 `24646a7ab2e960bd1d5ff885b1df9ad6f3bad7c1`，该旧结果不覆盖本批新增 38 条接口。

## 固定证据

以下为本地审计入口。私有环境、密钥、Cookie、完整运行日志及 XML 不随源码交付。

| 证据 | SHA-256 | 本地入口 |
|---|---|---|
| 最终定向集成包 | `c3d33337d8996f6dcdbdabd6c97882b35a94aedbb5b5643f410df74f83015a85` | `/tmp/msf-g014-remaining-reads-integration-host-20261104/attempt3/packet.json` |
| 完整候选 843 输入清单 | `507852bcf2caf668777d8d5e2576b780d6f273840a84347b831dcb9bd88f85e5` | 同目录 `source-manifest.json`，基于 `392b4b572a7d6725e85c36dd1868dcaab1c50cf8`；不含本文后增文档 |
| 最终编译／运行绑定 | `39926e40d8fc56cd6638564478ada15053d82b147155620934a0dc73ca8b256b` | 同目录 `runtime-bindings.json` |
| 共享快照 9 项包 | `0d6d763b1218fdf48c2afe3d76316eca0cb38c0d6d962d20f6e581b49f6c0a05` | `/tmp/msf-g014-read-snapshot-host-r3-20261104/packet.json` |
| 查询／历史 33 项包 | `082c5a6e1b554bc8d5eac7ed8d86f2543bc0790eaf96eaf5334cfd29b56aa344` | `/tmp/msf-g014-query-history-host-20261104/attempt3/packet.json` |
| 元数据／选择器 79 项包 | `706fd57587014c87155cd1883c33e8d5344defb354d676de90516f1cee14390e` | `/tmp/msf-g014-metadata-selectors-host-20261104/attempt4/packet.json` |
| Node 所有者状态包 | `38a815663979d017111cdd6ead173077efc1d654dcdbec433fdf20ee362099d8` | 同上父目录 `node-regression/packet.json` |
| 共享快照独立复审 | `e04218a5f0fb12c2093056a145d0776c7104801a88af9307d510a139aeb04906` | `/tmp/msf-g014-shared-read-schema-independent-code-review-r2-20261104.md` |
| 查询／历史独立复审 | `57b2f1b4e0c44aa06483131ff7026e753139930ce91db0c91de78ab4cc94fa17` | `/tmp/msf-g014-query-history-independent-code-review-r3-20261104.md` |
| 元数据／选择器独立复审 | `6d9ebd0db5cfd8c6020b84b71116852b5e1f36f8347ab79356e9dbbb3403a3c0` | `/tmp/msf-g014-metadata-selectors-independent-code-review-20261104.md` |
| 本批托管 CI 证明 | `cf57f7a655f28a4c201ad60a1f049956881c3871b7cfa590708a5d8c2e9952ab` | `/tmp/msf-g014-remaining-reads-git-delivery-20261107/ci-proof.json` |
| 后端原始 job 工具结果 | `85065751a57320decd81292ad30e558feeb9a791fb7cdae3dca43084ea335b7c` | 同目录 `backend-ci-job-128015444319.json` |
| 前端原始 job 工具结果 | `e61f0d7c00dfe372aaeef1c5b6ed368a1f3c20642fc282d81bc61695c367fd41` | 同目录 `frontend-ci-job-128015444734.json` |

三份复审均无新增问题。共享快照复审只覆盖其 9 项前期图；查询复审覆盖 33 项前期图。元数据复审另外核对最终 32 份 XML 及其所属 22 文件哈希，但没有重新审计整个 843 输入／1231 class 图。这些限定批准不替代完整生产验收。

## 仍待验收与开发

- 实际 PostgreSQL SQL、排序规则、RLS、非所有者运行角色及并发撤权／回滚行为。
- 持久 HUMAN 身份与完整授权过滤链下的业务 HTTP／JSON 契约闭环，以及真实数据库中的查询、游标、历史版本标签和候选资格。
- V21 尚未应用或授予目标函数执行权限；既有 V15–V17 增量权限方案与历史 V9 原件问题保留原门禁，不新增角色、密钥、NAS 服务或外部投递，不用修复／基线／乱序迁移绕过。
- 六类实体的真实前端消费与导航接线，以及 G014 完整风险识别、评估、管控措施与后续检查闭环仍需开发；不以基础台账接口完成代替风险业务完成。

本批已发布面向 dev 的草稿 [PR #49](https://github.com/mightyoung/might-software-factory/pull/49)，上述源码提交的完整托管单元／构建验证已通过。本文没有执行远端合并、部署、数据库变更或新增权限。
