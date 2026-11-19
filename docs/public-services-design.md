# 公共服务设计 v1.1

设计基线 v1｜2026 年 10 月 29 日｜云端公开资料核验及方案设计。本文把已同意的交互和权限原则落实为公共服务方案，常规选择由助手按授权定案；不包含业务规则、编码、部署或采购授权。没有运行集成、性能、安全或恢复实验；所有容量数字是待测起点，不是承诺。

**版本导航：** v1.1 语音增量见第14节，已确定代码配置测试后发布、首期仅电脑浏览器，以及最小登录加载和性能门禁；涉及这些范围以 v1.1 为准，以下 v1 原文保留供追踪。

**结论：Java 与 Spring 模块化单体，PostgreSQL 作为唯一完整功能数据库，嵌入式 Flowable 审批，服务端持久会话，RBAC 结合数据范围。首期不引入微服务、独立身份服务器、消息中间件或对象存储集群。Redis不作为框架硬依赖；当前测试环境使用外置LAN Redis，见[测试环境](test-environment.md)。** 用清晰模块和契约限制 AI 生成代码，而非自行实现密码学、流程引擎或任意脚本平台。

# 1 方案边界与取舍

三层仍是公共能力、业务、展示；纵向的语义和能力登记贯穿三层。登记对象、字段、关系、动作、权限、错误与接口版本，不是另一套业务数据库。页面和 Agent 必须调用同一业务服务，不能绕过授权直接执行 SQL。

Java 胜在此次 CRUD、组织权限、审批及运维组件整合成本，而非断言比 Rust 更稳定。Rust 可保留为将来独立的性能敏感组件，但不在公共底座引入双语言服务。可靠性由事务、资源限制、恢复、观测及测试形成。

**默认定案与延期：**

- 完整功能档仅 PostgreSQL；不承诺 SQLite 与 PostgreSQL 无成本互换。SQLite 仅未来独立离线/演示适配，不能替代本方案的审批、RLS 和并发验收。

- 普通 CRUD 不需要向量数据库。需要语义检索时再引入 pgvector 扩展，仍用正常权限过滤；向量索引不是权限模型。

- Redis 留接口但不做硬依赖；热点数据先短期进程缓存，安全判定不能依赖可能过期的无限期缓存。

- 不上 Kubernetes、服务发现、配置中心、网关集群、独立 MQ。只有明确吞吐、独立伸缩或外部订阅需求才增加。

- 没有把大厂局部技术栈当成熟度证明。Oracle 的 shell/header/drawer 模式支持应用外壳分离的参考，但不证明我们的布局已获得用户可用性验证；本轮没有独立用户研究样本。[Oracle 应用外壳](https://docs.oracle.com/en/cloud/paas/integration-cloud/visual-developer/edit-app-header-and-footer.html)

# 2 技术组合与兼容门槛

| 部分 | 设计选定 | 取舍和上线门槛 |
| --- | --- | --- |
| 运行时 | Java 21 LTS，选择有明确安全维护渠道的 OpenJDK 发行版 | 不把 Oracle JDK 商业条款泛化为所有 OpenJDK；安装前核选定发行版许可证与支持期限 |
| Web | Spring Boot 4.1.2、Spring MVC、Bean Validation | 当前官方文档版本；统一 BOM，不单独随意升级底层库 |
| 模块 | Spring Modulith 2.1.1 | 以模块依赖检查、集成测试与持久事件机制约束边界，不意味着拆进程 |
| 身份 | Spring Security 7.1.2、Spring Session JDBC 4.1.1 | Boot BOM 管理；PostgreSQL 持久会话，OIDC Client 标准入口 |
| 数据 | PostgreSQL 18、jOOQ OSS 3.21 系列、Flyway 开源模块 | SQL 显式可审查，复杂数据范围优于隐式对象加载；代码生成和迁移纳入 CI |
| 审批 | Flowable 8.0.0 开源嵌入式 BPMN | 不嵌入商业套件，不外露引擎 REST；仅用受限审批模板 |
| 观测 | Actuator、Micrometer、结构化日志，按需 OTel 导出 | 管理端点内网/鉴权；不把审计与运行日志混为一谈 |
| 前端衔接 | React、TypeScript、Vite；路由/请求/表格用维护中的成熟组件 | 本次定公共 API 和交互契约，不锁未经核验的完整前端依赖树 |

**版本不是拼名词：** Boot 当前官方页为 4.1.2；Modulith 2.1.1 标签 POM 使用 Boot 4.1.1。Flowable 8 明确要求 Boot 4 / Framework 7，其发布源码依赖基线为 Boot 4.0.2。因此上述升级组合是设计候选锁定线，**尚未有端到端兼容通过的证据**。第一道开发门禁必须验证启动、建表、事务回滚、审批计时器、Security/Session 集成；失败不得上线或强行覆盖依赖。应在同主线维护版本内修正并记录 ADR，不无声退到停维护主线。[Boot 要求](https://docs.spring.io/spring-boot/system-requirements.html)、[BOM](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)、[Modulith 标签 POM](https://raw.githubusercontent.com/spring-projects/spring-modulith/2.1.1/pom.xml)、[Flowable 8 发布](https://github.com/flowable/flowable-engine/releases/tag/flowable-8.0.0)、[Flowable 依赖](https://raw.githubusercontent.com/flowable/flowable-engine/flowable-8.0.0/modules/flowable-dependencies/pom.xml)。

jOOQ OSS 的数据库支持矩阵有限，不能将最新 OSS 自动配旧 PostgreSQL；本案 PG18 与 3.21 线一起选。普通数据层只用 jOOQ，不再混入 MyBatis 与 Hibernate/JPA 三套风格。Flyway PostgreSQL 需独立数据库支持模块，迁移由专门发布身份执行。[jOOQ 矩阵](https://www.jooq.org/download/support-matrix)、[版本区别](https://www.jooq.org/download/versions)、[Flyway PostgreSQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)。

**许可与维护决策：** Spring 核心、Modulith、Flowable 开源引擎与 jOOQ OSS 采用 Apache 2.0 路线；PostgreSQL 用 PostgreSQL License。最终发行需生成 SBOM、逐包核许可证及漏洞，不能把商业附加功能当免费开源。Keycloak 虽为 Apache 2.0，但暂不增加独立服务。Camunda 7 CE 已停止后续安全维护；Camunda 8 自托管核心生产许可不符合本案默认无新增商业成本，因此不选。MinIO 官方仓库已于 2026-04-25 归档且为 AGPLv3，不作为新建默认；RustFS Apache2.0 仅后续候选，不能以 Rust 名称推断可靠性。[Camunda 生命周期](https://camunda.com/blog/2025/02/camunda-7-enterprise-end-of-life-extension/)、[Camunda 许可](https://docs.camunda.io/docs/reference/licenses/)、[MinIO 状态](https://github.com/minio/minio)、[RustFS 安装与模式](https://docs.rustfs.com/en/installation)。

# 3 模块所有权和公开接口

一个后端进程、一个主 PostgreSQL；按模块包和表名前缀分治。模块仅通过公开接口或版本化事件交互，禁止业务插件直接更新公共表或引擎表。模块依赖必须无环；跨模块读模型允许通过公开查询接口组装，不用任意跨表脚本。

| 模块 | 独占状态 | 公开能力 |
| --- | --- | --- |
| identity | account、credential、外部身份、session 索引、邀请/重置凭证 | 登录/退出、密码重置、会话撤销、OIDC 绑定 |
| tenancy | tenant、tenant_application、tenant_member | 当前成员上下文、停复租户、应用开通 |
| directory | org_unit、member_org、position、member_position、group | 组织查询、有效成员解析、调岗影响预览 |
| authorization | role、permission、grant、scope_rule、版本号 | decide、buildDataScope、explain、权限变更 |
| application_registry | application、menu、entity_schema、action_schema | 当前用户导航、字段/动作契约、版本能力目录 |
| workflow | 引擎 schema、定义版本映射、业务关联、处置记录 | start、claim、complete、transfer、cancel、retry |
| support | file、dictionary、config、notification、job | 统一文件口、类型字典、配置读取、通知和后台任务 |
| audit_ops | audit_event、安全事件、trace 关联 | 查审计、查有效授权原因、合规导出和运维健康 |
| business_* | 各业务自己的聚合表 | 业务校验和状态变更，调用公共接口而非复制规则 |

核心调用上下文固定为 actorId、accountId、tenantId、memberId、principalType、tenant/member auth_epoch、人类授权方、requestId、traceId；从验证过的会话/令牌得到，禁止相信客户端随意传来的 tenantId 或角色。actorId 指审计主体，accountId 用于校验全局账号状态，两者不能默认相等。

同步事务用于必须一起成功的状态：业务提交状态 + 审批实例关联、角色变更 + 安全版本号 + 审计、组织移动 + 来源授权调整。审批同库同事务管理器时参与同一事务；必须以回滚测试证明，不能只靠注解假设。

通知、外部邮件、导出与文件扫描用提交后持久事件/作业；不能在数据库事务中长时间发 HTTP。采用 Modulith 持久事件注册表，不再额外维护一套重复业务事件 outbox；外部投递记录独立消费幂等键。原事务提交不意味着外部副作用恰好一次。[Modulith 事件语义](https://docs.spring.io/spring-modulith/reference/events.html)

# 4 核心实体和约束

下表是逻辑 schema，不是已执行 DDL。主键为不携带业务含义的 ID；所有租户数据均有不可空 tenant_id。可变聚合含 version、created_at、updated_at、操作者；时间存 UTC，展示按用户时区。租户内外键采用 tenant_id 加实体 ID 复合约束，避免同库跨租户引用。

| 实体组 | 关键字段与关系 |
| --- | --- |
| account、external_identity | 全局账号状态；外部唯一键 issuer + subject；手机号/邮箱不可直接当永恒身份；凭证单独存 |
| tenant、tenant_member | tenant 稳定 code、状态、auth_epoch；member 唯一 tenant + account，含状态；账号可有多个租户成员身份 |
| org_unit、member_org | parent_id 同租户、防环；code 租户唯一，同级名称唯一；member_org 含 PRIMARY/CONCURRENT、有效期，仅一个当前主部门 |
| position、member_position、group_member | 岗位和群组独立；岗位关联部门及有效期；群组不等同部门；成员失效立即影响新授权判定 |
| role、permission、role_permission | 稳定 permission code，如 entity.action；角色租户局部；系统内置角色允许复制但不能让租户扩大平台权 |
| role_grant、scope_rule | 授予对象 member/org/position/group；记录来源、有效期、授予者；每个 permission 可绑定 SELF、DEPT、DEPT_TREE、指定组织/关系范围 |
| application、tenant_application、menu | 平台应用定义、租户 entitlement、菜单 route/action permission 分离；菜单不是授权凭据 |
| workflow_definition、workflow_binding | tenant、流程 key、不可变版本、校验摘要、状态；binding 保存业务类型/ID/业务版本/流程版本/实例 ID |
| workflow_decision、assignment_snapshot | task ID、候选/实际处理人快照、动作、理由、幂等键、前后版本；保留代理和转交来源 |
| file_object、file_link | tenant、随机 object_key、大小/类型/摘要、扫描状态；关联 businessType/businessId；对象访问受业务权限约束 |
| dictionary_item、config_entry | tenant 或平台范围、key、类型、版本、启停；引用保留稳定 ID；密钥只保存 secret reference |
| event_publication、job、delivery | 事件持久状态；作业 tenant/type/due/lease/attempt/result；投递按 event+recipient+channel 唯一 |
| audit_event | tenant、actor、delegator、action、resource、结果、理由、脱敏差异、traceId、policyVersion |
| saved_view、query_history | tenant+member+entity+schemaVersion；视图含过滤/排序/列；历史与明确保存的视图分开 |

平台级账号/租户注册表不伪造 tenant_id=0 混入业务范围；单独平台 API、权限与数据库访问路径。停用替代普通删除，已被业务引用的组织/字典/账号不能物理删除历史证据。隐私删除与法定保留另走受控程序。

# 5 身份 租户和权限

## 登录与会话

首期使用成熟 Spring Security 认证与密码哈希接口，禁共享默认密码；邀请和重置使用短时一次性随机令牌，仅存哈希，消费原子化。失败限流与短期锁定，避免以账号永久锁定制造拒绝服务。登录响应避免暴露账号是否存在。

浏览器使用 Secure、HttpOnly、SameSite Cookie，启用 CSRF，登录轮换 session ID；凭证不放 localStorage。同域反代 /api 优先，前后端分离指代码与部署边界，不要求跨站 Cookie。设计默认空闲 30 分钟、最长 12 小时，管理员敏感动作再认证；为可配置默认而非法规要求。退出、改密、成员停用、权限撤销可撤相应会话/安全版本。SSO 用 OIDC Client；不自行写授权服务器。只有实际联合身份/MFA 管理需求出现时引入 Keycloak 或既有企业 IdP。

最长会话期限已按成功登录的服务端时刻实现，配置 `software-factory.security.session.absolute-timeout` 默认 `12h`，访问不续期；旧会话缺失时刻或时刻无效均需重新登录，主体验证结构不变。登录入口明确限定 POST。测试与在途请求边界见[最长会话期限切片](verification/session-absolute-lifetime-2026-11-02.md)；敏感动作再认证仍未实现。

登录入口已补进程内来源/全局尝试限流及来源与规范化账号的累计失败冷却；不因切换租户分桶，不修改账号状态，响应为通用429和正整数Retry-After。成功尝试也消耗速率预算；失败窗口内成功不清零累计失败。默认5分钟内5次坏凭据触发30秒冷却，具体配置、并发预留、内存上限和实测见[登录尝试防护切片](verification/login-attempt-throttle-2026-11-02.md)。这不代表跨来源账号聚合或跨实例共享限流已实现；代理来源解析及发布容量仍需另验。

会话主体已提供稳定的账号UUID名称，用于JDBC Session账号索引；同账号多租户归组，不同账号隔离，脱敏文本和序列兼容保持。旧活跃索引可随正常请求保存归正，未访问旧行没有全量回填。按账号查索引可能包含未清理的过期会话，不能当作认证或授权证明；公开会话管理/批量撤销接口尚未实现。测试与兼容边界见[账号会话索引切片](verification/account-session-index-2026-11-02.md)。

本地登录通过 `POST /api/v1/auth/login` 校验 login_key、密码和租户 code。认证只读事务从真实身份表解析 account、HUMAN principal、tenant/member、auth_epoch 和 credential_version；成功后由独立 JDBC Session 事务保存服务端主体及认证时刻，不把认证读取与会话写入称为同一原子事务。租户 code 只是选择条件。密码只接受 `{bcrypt}`、work factor 12–16；编码默认12。受保护请求拒绝缺失/旧凭据版本，旧序列缺失版本保持0。自助改密已实现哈希、版本递增及成功审计同事务，失败事件独立记录；重置入口仍待后续。登录保存故障会在未提交响应时转为统一503并清除可发布认证Cookie；框架提前flush可能在finally再次保存，因此503不代表数据库必定回滚。独占库上已有16项登录、8项改密、3项故障恢复与3项独立JVM会话验收，另两项JDBC序列化不计作身份表HTTP证明。最新范围见[身份会话闭环验收](verification/identity-session-closure-2026-11-02.md)，早期记录见[身份登录实库验收](verification/identity-login-postgres-2026-11-02.md)。账号初始化、邀请/重置、登录成功/失败持久安全事件及共享限流仍未完成，不宣称可投产登录配置。凭据兼容见[凭据版本切片](verification/credential-version-boundary-2026-11-02.md)。

登录JSON严格按已有契约读取三个必需字符串字段；拒绝未知/重复字段、错误根类型、尾随JSON值及非字符串字段，不接受客户端指定身份或凭据版本。请求体仍限4096字节，错误响应不回显凭据或解析细节。凭据版本检查不锁账号；独立授权读取配置RR，若已进入工作流事务则加入外层隔离级别，不保证取消在途请求。

BCrypt实际认证另要求密码为有效UTF-8且长度1–72字节；超过该字节边界直接按通用错误凭据拒绝，不允许截断前缀匹配。JSON字段原1–1024字符格式上限不变。未来凭据写入也必须遵守算法字节边界；曾依赖截断匹配的长密码不再接受，需重置。

## 租户与组织

每请求确认当前 tenant 和 member 有效、应用开通、权限版本新鲜；同一账号跨租户切换显式执行并重新生成上下文。停用租户不冻结其成员在其他租户的账号。停用先预览受影响应用、用户、审批及作业；阻止新交互与任务领取，长任务按可安全中断点暂停，任何写入提交前复核状态。恢复不自动重发已成功外部副作用。

调主部门与新增兼任分开。按授权来源撤旧部门派生 grant，不撤无关个人授权。岗位/兼任按有效期计算；历史业务 org 快照不随着员工移动。成员离职先处理待办/文件所有权，保留审计与历史关联。

## 授权计算

有效许可 = 账号及成员有效 ∩ 租户有效 ∩ 应用开通 ∩ 动作权限 ∩ 数据范围 ∩ 对象状态/业务约束。RBAC 管动作，部门、归属、参与者等属性/关系管数据，不靠“超级角色”无限放权。多个允许范围可取并集，但始终受租户边界、禁止状态和业务硬约束相交限制。首期不引入用户任意编写的策略脚本。

列表、计数、联想、详情、修改、删除、附件、导出、审批和 Agent 都执行同一规则。查询的数据范围编译为参数化 SQL；ID 猜测、筛选或排序不能绕过。记录版本冲突返回 409，不做后写覆盖。

PostgreSQL RLS 作为业务/公共租户表的第二道防线，运行身份非 owner/superuser/BYPASSRLS；采用 FORCE RLS 与事务局部 tenant 设置，连接回池必须没有上下文残留。RLS 不能保护所有表级操作，迁移账号与备份账号另控。引擎内部表不盲目套自定义 RLS：审批隔离通过门面强制并专测。[PG 行安全](https://www.postgresql.org/docs/18/ddl-rowsecurity.html)、[OWASP 授权原则](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)。

平台管理员可管理租户生命期，不自动获得租户业务读权限。需要支持访问时采用指定租户、指定目的、限时授权、完整审计，不提供永久全库浏览按钮。

## Agent 入口

Agent 仅能调用登记动作，服务身份和代表用户的授权分开记录；有效权限不得高于委托人和授予 Agent 的 scope 交集。短期、可撤销授权；不能从提示词获取管理员权限。查询默认只读，授权变更、批量删除等高风险动作需要明确人类确认凭据。幂等键、审批、速率/数据量限制与人类入口相同；审计同时记录 Agent 和委托人，不记录敏感完整提示词。

# 6 通用审批

首期交付受限 BPMN：开始/结束、人工节点、条件分支、顺序/并行、超时提醒、白名单服务动作；不向租户开放任意脚本、Java delegate 或上传可执行流程附件。业务决定“何时需要审批、审批后改变什么”，公共模块提供运行、找人、记录及异常机制，不内置隐患等特定业务规则。

- 定义草稿可修改，发布后不可变；版本记录校验摘要、formSchemaVersion 和解析规则版本。发起锁定具体 definitionId，重试不能重新取 latest。

- 存量默认留旧版；停用定义阻止新发起，不消失旧任务。迁移是受控、逐实例检查的单独动作，不静默升级。

- 按业务记录的部门和配置岗位/角色找人，不按发起人的默认部门猜测。无办理人进入显式兜底或异常，绝不自动通过；多人明确或签、会签或顺序规则。

- 创建任务快照候选/实际成员；认领原子化，竞争者收到已认领反馈。转派要资格校验、理由和审计；转派后原办理人不能继续批准。

- 完成动作校验租户、成员、参与资格、权限、任务状态和版本；同幂等键同请求返回原结果，不同请求体冲突。拒绝属于业务终态，不当技术故障无限重试。

- 撤回/取消只能走预定义受控转换；已发出的外部副作用不能用“撤回”假装自动撤销。

- 超时默认提醒，不默认自动通过。错误分配置错误、无人可办、外部失败、并发冲突；技术错误退避有限重试，耗尽进人工异常台账。

- 所有审批、历史、变量和附件访问经 WorkflowFacade；Flowable 的 tenantId 不提供完整授权，按 ID 操作必须读回租户并检查；不外露引擎 REST。[Flowable 多租户](https://www.flowable.com/open-source/docs/bpmn/ch18-Advanced/)、[发布版本](https://www.flowable.com/open-source/docs/bpmn/ch06-Deployment/)、[Spring 事务集成](https://www.flowable.com/open-source/docs/bpmn/ch05-Spring/)。

# 7 文件 字典 配置 通知和任务

## 文件

首期私有持久目录，不在 Web 静态目录；StoragePort 为唯一存储接口。文件内容不能省略持久化和备份，即使不部署“文件服务器”。对象随机键不采用用户路径；上传隔离、大小/类型/签名验证、扫描通过再关联业务。扫描不可用时保持隔离，不默认为安全；离线演示仅允许受限格式和清晰提示。下载和预览执行关联资源权限，HTML/SVG 等主动内容不直接同源执行，必要时附件下载或隔离域。

数据库与文件系统不能原子提交：状态为 UPLOADING、QUARANTINED、READY、DELETING、FAILED，上传先临时对象、确认摘要后幂等标 READY；清理孤儿文件有宽限期、重试和日志。删除先撤访问再异步清对象，不能凭失效业务外键误删仍被引用的对象。[OWASP 上传指南](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)。

隐患整改使用文件证据时，`evidence_item.verification_level=CONTENT` 只能作为投影，必须同时取得可信服务端核验回执，回执绑定tenant、fileId、SHA-256摘要与存储版本；提交还要证明`evidence_item.sha256`、文件元数据摘要和回执摘要相同。提交时同一事务写入不可变的`remediation_submission_evidence`快照，固定file_link_id、file_id、摘要、storage_version、receipt_id及verifier/key/policy签发上下文。数据库锁住提交和证据集，计算全部证据项的规范有序ID manifest/count，校验完整后才允许一次性封存；延迟约束拒绝未封存的提交，封存后拒绝新增快照或扩展该证据集。验收核对持久manifest/count并按receipt_id校验，读取只能指定快照中的不可变storage_version，不得按file_id读取latest。数据库行锁不等于对象存储不可变性。核验器未连接、文件状态非READY、引用/归属无效或回执不匹配时拒绝提交；V6加入快照、只追加、封存和重绑定守卫。

当前代码提供一条可选本地 adapter 和一条封存证据内容读取端点。adapter 只在显式提供私有根目录、receipt HMAC 信任密钥和 key ID 时创建；默认配置仍用 fail-closed adapter，不会扫描、创建磁盘根目录或创建 receipt registry；部署方须预先提供由运行服务账户拥有、POSIX 权限仅对 owner 开放的私有根目录及其 `.receipt-registry` 子目录，adapter 会校验两个目录没有 group/other 权限且 owner 有读写执行权限。文件位置由服务端固定派生为`root/{tenant UUID}/{file UUID}/{storageVersion}.blob`，不使用客户端路径或数据库`object_key`；版本标识受限字符集，已封存的 blob 必须是服务账户拥有、仅 owner 可读、没有 POSIX 写权限的 regular file，且 `unix:nlink` 必须为 1；读取不跟随符号链接的安全目录句柄，并校验文件身份、链接数、长度和摘要。读取链接数或 POSIX 权限能力不支持时 fail-closed。核验入口只允许事务外调用：它散列实际文件内容后签名并写入 HMAC 保护的 receipt registry；提交业务事务只查现存回执，不触发文件读取、扫描或签发。读取端在返回前将固定版本内容放入有 64 MiB 总内存预算的缓冲并校验摘要，再分块写入响应，避免校验后磁盘文件被原位改写的竞态；超出并发预算的请求失败关闭。`GET /api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-items/{evidenceItemId}/content` 只接受业务资源 ID，按本请求解析到的 finding 读取授权快照、当前未撤销的 file link、READY 状态和已封存快照解析版本；响应作为附件并禁用缓存。本请求的授权判断使用解析到的 principal/member/role/grant 快照；授权来源未与撤权流程共同加锁，因此授权已解析后、读取事务提交前发生的撤权仍可能不改变本请求的结果。jOOQ 查询的 `FOR SHARE` 只覆盖 `file_link` 和 `file_object`，不保证授权来源在本请求期间最新。后续请求会重新解析授权；已在途响应可能发送已校验缓冲，不承诺撤权能中止响应或撤回已发字节。

当前 adapter 只持有一个 HMAC key ID，不支持验证历史 key ID。更换或删除该 key 会让其签发且仍被引用的回执无法验证；部署不得直接轮换/移除它，除非已有历史验证 keyring，或受控重签后确认旧封存快照不再依赖它。当前没有创建或配置真实密钥，因此这是部署前门槛。

后续 S3 适配器复用权限接口。签名 URL 短时且不进日志/永久字段，敏感下载可走代理；已签链接在过期前可能仍可用，不能承诺撤权限即全部失效。采用不重复 object key 防上传覆盖。[S3 官方语义](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)。

## 前端公共 Office 能力（设计建议）

工装、隐患等应用可以在有权访问的业务记录上提供“打开文档”。各应用传入文档引用与打开意图，前端公共 Office 模块统一打开编辑器，处理加载、只读展示、未保存变更和退出。Univer 运行在独立加载的编辑页面中。服务端按当前用户、租户、关联资源和动作核验权限，按版本保存文档。若审批需要冻结材料，审批记录应引用提交时的文档版本。

首个验证范围为单人在线表格，覆盖保存后重新打开、两人同时修改的冲突提示和跨租户访问拒绝。Office 文件格式转换与实时协作分别评估许可、服务部署和业务必要性。详见[公共 Office 集成评估](univer-office-integration-2026-11-19.md)。

## 字典和配置

字典是稳定 ID、code、label、顺序、状态和范围；禁用不破坏历史显示，翻译/改名不换业务 ID。系统字典与租户字典分开，租户只能覆盖明确允许项，不能把自由文本变成系统权限。

配置按平台默认→租户覆盖→个人偏好分层；类型 schema 校验、版本化、预览影响、审计、恢复上一版本。密钥不回显、不入普通配置表，日志只记 key 和变更发生。安全策略变更提升 policyVersion，不能等进程缓存自然过期才撤权。

## 通知与作业

首期站内通知，邮件/SMS 适配器默认未启用；没有自动开通付费渠道。模板有版本、变量白名单和转义，深链进入页面后再次验权。已读状态与实际送达状态不同。

持久 job 由进程内调度器轮询，租约和原子领取防多实例重复；任务存租户、类型白名单、payloadVersion、执行时间、attempt、lease、取消标志和结果。到期租约可回收，执行前恢复可信上下文，权限撤销/租户暂停复核。采用退避与抖动，明确最大次数、失败队列、授权重试。批量任务每项独立幂等，输出成功/失败/跳过原因，不假装全成功。

事件投递至少一次；外部系统已收到但响应丢失标 UNKNOWN，用供应商幂等键或查单解决，不盲目重发。业务事件注册表不是定时任务表，不混用；首期无需 Kafka/RabbitMQ/NATS/Quartz。

# 8 日志审计和权限解释

三类分存、分权限、分保留：

1. 登录/安全：成功失败、退出、改密、凭证/授权变化、拒绝访问。失败事件独立记录，不随业务事务回滚消失。

2. 业务审计：人/Agent、委托人、租户、资源、动作、结果、理由、脱敏差异、traceId、策略版本。关键写操作与审计原子提交，审计失败则该写入失败。

3. 运行诊断：结构化错误、延迟、资源、依赖和任务积压；不记录明文密码、会话、签名 URL、完整身份证或无必要的正文。

设计初始保留值：运行日志 30 天，安全事件 180 天，业务审计 365 天；仅测试/初始运维默认，不声称满足所有行业法规。正式上线根据客户合同与监管要求改为可审计策略；合法保留锁优先于自动清理。普通租户管理员只能读本域授权日志，不编辑审计；运维不能借日志自动获得业务明文。

explain 返回允许/拒绝、授权来源链、有效期、数据范围摘要、策略版本；仅本人或授权管理员可看，不泄漏对方租户实体。权限变更前提供影响预览；变更后旧授权版本失效。追加式表不是强防篡改证明，未来有合规需要再引入独立归档/WORM。

Actuator 管理面内网可达并单独授权；健康检查区分 liveness/readiness，指标含请求延迟、连接池、作业积压、审批错误、磁盘和备份失败。高基数 userId 不作普通指标标签。[Boot 观测](https://docs.spring.io/spring-boot/reference/actuator/observability.html)、[Boot 审计](https://docs.spring.io/spring-boot/reference/actuator/auditing.html)。

# 9 前端和 Agent 共用的契约

## 导航

默认顶部为工作台、系统设置和已开通且有权进入的业务应用；不为了每个后端模块新增顶部入口。系统设置内按身份组织、权限应用、审批配置、公共支撑、审计运维分组；左侧最多两层。顶部先单级，数据结构允许分组，选择结果仍是应用。头像入口提供个人资料、个人会话和退出；租户切换与应用切换明确区分。

鼠标悬停可预览，但不能作为唯一进入方式；点击、键盘、触屏均可用。无可用功能的应用不展示；直接 URL 访问仍受后端校验。路由状态含应用与租户，避免多标签页用可变 session.currentTenant 导致串租户。

## 字段驱动查询

EntitySchema 包含 key、schemaVersion、字段类型、nullable、可查/排序/导出/显隐、允许运算符、引用资源、格式与敏感级。ActionSchema 含 actionKey、页面/批量/行位置、标签图标、permission、状态条件、确认等级、幂等要求及结果协议。显示条件不替代后端授权；业务特定动作仅接声明与服务处理器。

| 字段类型 | 默认查询 | 展示/输入 |
| --- | --- | --- |
| 文本 | 等于、包含、为空 | 转义文本；长度限制 |
| 数值/金额 | 等于、比较、区间、为空 | 单位/精度；金额不用浮点语义 |
| 日期/时间 | 起止、早于、晚于、为空 | timezone 明确，日期不隐式变 UTC 时间点 |
| 布尔/枚举 | 单选、多选、为空 | 稳定 value 与显示 label 分开 |
| 用户/组织/关联 | 指定 ID、多选，组织可声明含子树 | 联想和弹窗并存，重名辅助信息；禁止自由文本冒充 ID |
| 图片/附件 | 是否存在；额外条件须明确声明 | 缩略图/安全预览/鉴权下载；不默认全文检索 |

过滤 AST 为 field/operator/value/group，首期 AND；需要组合时用有深度/项数限制的 AND/OR。字段白名单、类型校验、参数化查询；不接客户端 SQL、任意表达式或排序列。null 与空串区分。表头和查询区共享 AST；查询/回车同一动作，重置恢复默认。Autocomplete 防抖、取消过期请求、最少搜索条件/分页限制，返回仅有权看的 ID+label。

结果分页默认 20、可选 50/100；稳定排序追加 ID 作为 tie-breaker，过滤变化重置页码。总数受相同权限；大表允许无精确 count 的游标模式，界面清晰区分。个人视图含过滤/排序/列，并带 schemaVersion；字段删除或权限变更时标失效条件，不偷偷扩大查询。历史仅存实际执行的结构化查询，敏感字段不持久化原值，可单删/清空。

## 批量与行操作

初期全选只选当前页，显示已选数量；跨页保持明确选择。全量命中选择是显式二次动作，生成短期服务端 selection token，绑定租户、actor、查询摘要及策略版本，不能接受浏览器“全部”布尔值。执行时重新检查每条权限/状态；更新的数据用版本冲突反馈。

公共框架提供查询、重置、视图、列配置、导出；新增/编辑/删除/导入/提交审批不是所有台账强制按钮，只有业务声明才出现。危险批量动作先预检显示预计可执行/不可执行数量与理由，再确认；长任务异步返回 jobId。结果按项反馈，禁止静默跳过。详情、用户和审批深链携带稳定资源 ID，不拼未转义外部 URL。

# 10 API 错误和一致性

统一 /api/v1，OpenAPI 描述请求/响应；金额、时间、枚举与空值编码一致。公共错误形状为 code、message、traceId、fieldErrors、retryable；不回显 SQL、堆栈和跨租户对象存在性。401 未登录、403 无动作权限、404 对无可见对象可统一隐藏存在、409 版本/幂等冲突、422 业务校验、429 限流、503 可重试依赖故障。

写请求支持 Idempotency-Key，存 actor+tenant+action+key 的唯一约束、请求哈希与结果；相同键不同内容拒绝。关键动作幂等历史随业务保留，不因 24 小时清理而重新提交审批；临时请求缓存可单独设置过期。乐观锁防覆盖，确需锁定时只锁必要记录、固定锁顺序、限制事务时长。

事件携带 eventId、schemaVersion、tenant、actor、occurredAt，不把完整敏感业务快照塞入所有消费者。新增字段向后兼容；破坏性 API/事件变化发新版本，给消费者迁移期。注册目录不是任意动态装包平台：新增模块仍需代码审查、迁移、测试和发布。

# 11 部署 迁移和备份

最小长期测试形态：TLS 反代/静态前端 + 一个 JVM 服务 + PostgreSQL + 私有持久文件卷；都可以在一台长期虚机，但不是高可用。2 vCPU、4 GB RAM、50 GB 磁盘只是低并发测试测量起点，**没有测试证明足够**；评估堆/连接池/流程历史/文件增长后再定。负载测试至少记录并发、数据量、P95 延迟、错误率、峰值内存、GC、数据库等待和重启恢复。没有负载目标就不发布“可支撑多少用户”的数字。

Sites 可承担前端展示的前提是托管能力、后端连通、CORS/认证部署方案另经验证；本设计不声称已支持。临时云电脑不作为长期在线后台。未注册、购买、开放端口或部署任何资源。

发布流程：版本锁定和 SBOM → 干净库建表 → 上一版副本升级 → 回滚/恢复演练 → 暂停会引起冲突的任务 → 备份 → 专用身份执行 Flyway 和已审查引擎迁移 → 启动/健康验证 → 恢复任务。生产禁引擎自动改表。采用 expand/contract；破坏性删列须过兼容窗口。应用回滚不等于数据库可逆，不能对已应用迁移做手工“修版本号”。

测试环境默认每日加密备份数据库、文件清单/内容和必要配置，每周恢复到隔离环境并校验业务—审批—附件一致；目标 RPO≤24h、RTO≤4h 仅作为待演练验收指标。进入真实关键业务前按影响等级增加连续 WAL 归档与恢复点、异地副本和监控。WAL 本身不是独立备份；需基础备份与完整归档链。[PostgreSQL PITR](https://www.postgresql.org/docs/18/continuous-archiving.html)。

数据库备份账户可见全数据的风险单列授权；备份不复用应用账号，不放同一块唯一磁盘冒充容灾。演练恢复时禁发通知/外部副作用；校验未完成审批、文件摘要、租户范围与任务重放状态。密钥恢复流程单独验证，不能只备份密文却无解密能力。

# 12 分阶段交付和验收门禁

**P0 兼容与边界验证：** 锁 BOM/许可，建立模块依赖测试；证明 PG18、jOOQ、Flyway、Session、Flowable 的建表和事务可共同运行。未通过前不把本设计变成“可部署成品”。

**P1 公共核心：** 账号/租户/组织/权限/应用菜单、日志、文件、字典配置、类型驱动列表、站内通知和持久任务；角色按来源、授权解释、租户隔离为不可裁剪核心。

**P2 审批与纵向能力：** 受限审批模板、版本、找人/异常、接口注册和 Agent 只读入口；写动作逐个声明权限与确认要求，不一次性开放全系统。

**P3 按真实需求扩展：** 企业 SSO/MFA、Redis高级缓存/分发能力、外部通知、S3/RustFS、复杂流程、物理租户隔离与 MQ。触发条件是已证明需求和成本收益，不因“架构完整”提前堆组件。

| 验收组 | 必须证明的负例和结果 |
| --- | --- |
| 兼容 | 新库/升级库均启动，BOM无冲突；流程启动、完成、定时器、事务回滚正确 |
| 越权 | A 猜 B 的业务/审批/文件 ID、恶意 tenant header、count/export/autocomplete：全部不泄漏 |
| 会话 | 改密全局失效，成员停用仅该租户失效，多标签切换不串租户；CSRF 请求被拒 |
| 数据库 | 连接池复用无 tenant 污染，直接漏 tenant 条件受 RLS 阻断，应用无迁移/绕过权限 |
| 授权来源 | 调岗只撤旧来源，个人角色保留；到期兼任即时无权；前端隐藏后直接 API 同样拒绝 |
| 并发 | 同时改记录得到明确409；双击审批只转移一次；幂等键换参数被拒 |
| 流程 | 无人/多人/离职/转派都有确定结果；v2 发布不偷换 v1；失败不自动通过 |
| 任务 | 事务前后/通知前后杀进程可恢复，重复投递不重复业务动作，UNKNOWN 不当成功 |
| 文件 | 路径穿越、伪 MIME、越权预览、恶意主动内容、扫描不可用均安全失败 |
| 审计 | 关键审计失败使写回滚，登录失败仍可追踪，敏感值不入日志，管理员不能改历史 |
| Agent | 提示词无法提升权限，scope交集执行，委托撤销后不能写，高风险动作缺确认失败 |
| 恢复 | 从备份恢复业务/流程/附件一致，实际记录RPO/RTO，恢复环境不发外部副作用 |
| 前端 | 仅键盘/触屏可导航，筛选和表头一致，批量不静默跳过，关联项不显示越权候选 |

上述是未来验收清单，**本次全部未执行**。本次完成的是资料核验和可实施设计，不产生提交、CI、测试覆盖率或容量成果。

# 13 定案状态和证据边界

已经设计选定：模块化单体、Java/Spring 主线、PG完整档、jOOQ单一数据访问、嵌入Flowable、持久Cookie会话、统一授权/数据范围、来源化授权、版本化审批、私有文件接口、数据库持久事件/任务、三类日志、字段类型查询和应用导航契约。

需要工程验证而不是用户偏好投票：Boot4.1 与 Flowable8 的组合、具体JDK发行版安全支持、所有传递依赖许可、负载和恢复目标、对象存储适配兼容。阻塞上线时由实施者提出证据和替代，不把这些宣称已通过。

只有出现实质业务歧义、监管/合同保留要求、外部身份或第三方成本、真实生产容量/可用性目标时再向用户提出具体问题。常规界面偏好和公共功能细节不再逐项打断。

主要补充官方来源：[Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)、[Spring OIDC](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)、[CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、[方法授权](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)、[Keycloak](https://www.keycloak.org/guides)、[PostgreSQL 许可](https://www.postgresql.org/about/licence/)、[React 自建框架取舍](https://react.dev/learn/build-a-react-app-from-scratch)。

研究覆盖：普通公开网页、官方文档及发布标签/POM；没有付费检索。MinIO 维护结论限定于所核官方开源仓库，不泛化为所有商业产品停服。RustFS 文档给出部署模式不等于独立可靠性实测。首次 RustFS 无语言路径与旧 OWASP 项目路径访问404，随后已核读正确官方路径。资料为动态页面，后续实施应固定标签、依赖锁与文档快照，不把本日版本永远视为最新。

# 14 版本 v1.1 台账发布和性能约束补充

2026 年 10 月 29 日 15:37 UTC（北京时间 23:37）增量记录。来源为本次语音确认：用户选“第一种”代码配置发布，明确“先不管手机…先管电脑”，并强调复杂表单和公共配置不能拖慢登录、导致断连或超时。本节在相应范围优先于 v1；前述原文保留，不改写历史。仍仅设计，未编码部署或测量。

## 台账配置与数据边界

- **第一版采用 AI/开发者在代码中配置，审查、测试、迁移后发布。** 不提供管理员在线新增字段并即时生效；现有普通字典、成员及授权管理不因此被禁用，但不能变成绕过发布的动态 schema 编辑器。

- 列表、表单、详情共用同一版本化字段定义，按页面声明显示、可编辑、必填和布局；业务可扩展控件/校验/动作，但走明确扩展接口和测试，不复制三套字段规则。

- 主记录与从属明细属于同一聚合时，同一事务提交，任一校验失败全部回滚；带主记录版本防并发覆盖。大明细的分批读取不意味着可以把一次提交默默拆成部分成功。

- 外部关联实体以稳定 ID 引用、独立生命周期；删除主记录不能误级联删除用户、组织、共享附件或其他独立业务对象。仅真正从属且明确声明的明细允许受控级联，审计及保留约束仍生效。

- 需要保留历史时，字段显式声明 snapshot 属性、来源、快照时点和版本；不能把每个关联字段无差别复制。关联目标改名/调岗不回写已冻结历史；详情区分当前引用值与历史快照。

## 第一版终端范围

仅电脑浏览器，优先常用桌面分辨率、键盘和鼠标；移动端适配、触摸专门交互和手机验收后置。因此 v1 第9节触屏和第12节触屏验收属于后续兼容目标，不是本版交付门禁。桌面键盘可达、焦点、对比度和错误反馈仍保留。

## 登录和首屏加载的硬边界

登录链路只完成认证及最小 bootstrap：当前身份、当前租户/应用上下文、当前应用必要菜单和权限摘要。应用切换器仅取有权应用的轻量 ID/名称，不拉所有应用菜单树、所有表单、全量字典、组织树或用户列表。菜单/按钮权限摘要只用于呈现，服务端最终鉴权一律不变。

当前页面 schema、字典和展示扩展按路由加载；非当前应用模块按需加载。组织/用户/关联候选分页远程检索，不一次下载全组织和账号。大明细分页或按分区加载，避免巨大 DOM 和全量 JSON；表单仅加载必要明细/字段，保留明确的尚未加载状态，不能把未加载当空值保存。

公共配置、字段 schema 和字典可按 tenant+application+version 缓存；支持 ETag/条件请求。权限/成员失效必须复核授权版本，不能因静态 schema 命中缓存而继续授权；失效范围只清相关版本，避免每次登录全量重建。敏感数据不得共享缓存键或跨租户缓存。

耗时导入、导出、批处理及报告生成返回 jobId，显示进度/失败与取消能力，不让浏览器长连接承担全部工作。普通请求有分层超时、取消传播和可定位 traceId；重复保存要幂等，网络超时不能提示成功，也不能让用户猜是否已提交。

## 资源上限与测量

服务器强制分页、最大返回字段/行数、筛选 AST 深度及项数、上传/提交大小和 SQL 执行时限；查询通过索引、EXPLAIN 与代表性数据复查，避免 N+1 和不受控 count。明细过大时明确拒绝并给出受控异步导入路径，不能靠无限增大超时掩盖架构问题。

建议先使用以下工程预算作为验证目标，不是已达到成果或 SLA：在明确网络、硬件、并发和数据规模的测试条件下，登录成功到可操作首屏 P95 ≤ 2 秒，普通分页查询 P95 ≤ 1 秒，常规聚合保存 P95 ≤ 2 秒。数据库/服务/反代超时由同一预算向下分解，服务端截止时间应早于外层，超限任务异步化；不能将每层各自超时简单相加。复杂业务超出预算需给出测量、原因和产品反馈方式，不能静默放宽。

验收覆盖冷启动/热缓存、权限变更、多个应用、大组织、长字典、复杂表单、多行明细、慢网络和断线重试；记录数据量、并发、请求数量、传输大小、P50/P95/P99、错误/超时率、SQL耗时及前端长任务。特别验证登录不请求未进入应用的重配置，查询无全量候选下载，保存异常不丢主从一致性，过期缓存不延迟撤权。

Java 不天然等于慢；Rust 也不会自动解决慢 SQL、网络往返、全量配置、巨大 DOM 和不合理请求链。先按链路证据定位 CPU、数据库、网络与渲染，再决定优化；不得以换语言替代测量。以上全部是新增设计与待执行验收，当前没有性能实验结果。

# 15 同日补充 单仓库与交付边界

## 单仓库与目录边界

目录与独立构建部署的唯一规范见[README：单仓库与目录边界](../README.md#单仓库与目录边界)。
