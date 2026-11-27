# Studio 发布适配器：静态复核与接线测试规格

本记录复核同目录的非迁移 SQL 草案，基线 `e64829d`。未连接数据库、未改授权，也没有实现适配器。

## 物理外键与审计

- 实际迁移 `V3__identity_directory_authorization_read.sql:19–27` 定义
  `tenant_member_pkey (tenant_id,id)`；`V5__finding_workflow_commands.sql:240–255`
  定义 `audit_event_pkey (tenant_id,id)`。目标列均为 UUID，且仓库实际已在
  V21 的 `asset_foundation_receipt`、V22 的风险草案和 V27 的工装目录采用同类审计外键。
  静态上不需要额外唯一索引或修改旧表才能引用这些键；延迟的是新表的审计外键，
  不是旧表主键。检查了至 V37 的迁移文本；此结论不是 PostgreSQL 执行结果。
  同租户但不匹配的审计动作/对象不能靠外键排除。
- 审计表只对白名单状态 `SUCCESS / DENIED / FAILED` 有检查约束；动作、资源类型和
  策略标签是有长度约束的字符串，`diff_redacted` 是 JSONB。复用现有 SUCCESS 插入
  不需要扩审计表的枚举或 CHECK。当前 CI 源测试角色的审计 INSERT 列清单已覆盖
  `JooqBusinessAuditAppend.commandInsertSql()`；这不是生产角色已获授权的证明。
- **若复用该 Java 审计适配器**：为选定的 Studio 资源在封闭的
  `RegisteredEntityAction` 注册 publish；`BusinessAuditRecord.ChangeKind` 增加
  `PUBLISH`，因为 `JooqBusinessAuditAppend:43` 将小写 kind 与可信 action 比较。
  选择并固定允许的字段类别（例如审批同意后增加 `PUBLICATION`），不得携带场景原文，
  并把第 24 行资产专用标签改为由受信注册动作选择的固定标签，现有资产动作标签保持不变；
  不接受控制器/请求传入任意标签或动作。发布资源 UUID 是加锁后返回的 stream UUID；
  `actor_id` 来自授权上下文，`publisher_member_id` 来自同一上下文的成员字段，不能互换。
- **权限接线另行审批**：确定 Studio 发布和 Player 读取各自的代码、作用域和拥有者；
  不复用 `ledger.configuration.publish`、通用管理或读权限替代发布，不默认授予现有角色。
  若使用当前 entity 动作链，代码须与 `RegisteredEntityAction.permissionCode()`
  派生规则一致，并加入实际动作解析/权限目录和策略决策路径；动作注册不等于角色获权。
  业务授权的权限不等于运行角色的 SQL 权限；新表访问和迁移账户 REFERENCES 权限
  需要单独审阅，现有表的审计写权限不在本草案里扩大。

## 最小 recorder 测试（在接线实现时添加）

不做自测式框架。把拟新增的薄命令/存储适配器作为被测对象，驱动真实
`StudioPublicationService` 和本地合成合法载荷，只替换它实际依赖的事务、受管会话、
审计与 JDBC 边界。按仓库的 `JooqBusinessAuditAppendTest` 模式捕获绑定的连接和参数。
用两个不同的连接标记验证所有步骤只用命令所属连接，越口立即拒绝而非隐藏重试。

| 场景 | recorder 应观察到 |
| --- | --- |
| 授权发布首版 | 顶层事务/租户绑定 → 共享授权 guard → 新鲜人类会话与发布动作 → 建流/锁流 → 版本 1 及预分配审计 ID → 同连接审计并匹配回执 → 空旧头的条件更新恰一行 → 事务提交后返回 |
| guard 后权限已撤回 / 会话失效 / 租户不匹配 | 只有 guard 与重新核验，零解码、零目标/版本读取、零审计；用无效业务载荷验证拒绝优先；不把较早的 capture 当可复用授权 |
| 正常重放、旧预期和指针异常 | 相同摘要且与当前版本相符只读；过时预期不写；指针零行或审计抛错整个命令失败，不返回成功 |
| 事务回调成功但提交失败 | 注入事务拥有者的提交异常而不是在回调内部抛错；适配器不得提前返回成功 |
| Player 授权 | 否决时零 SQL；pin 绑定 tenant/screen/version 且缺失不 fallback；alias SQL 同时绑定 tenant/screen；返回对象继续经过现有摘要和坐标范围校验 |

`LedgerRuntime:128–163` 已有 guard 后才使用 `withGuarded` 再拿 action scope 的例子；
其定义由 `ManagedSessionSensitiveSecurityTransaction.withGuarded` 实现。
现有 Studio service 的 `Caller` 只是领域数据，不是这个受管 scope；其事务端口接线
应借用外层物理事务，不能在新事务里拿锁，也不能靠冻结的 Caller 保证权限新鲜。
在真实 Studio 权限/策略与事务拥有者未确定前，不新增会假装完成这项绑定的接口或
mock 实现。recorder 只能证明适配器顺序、参数和错误传播；锁冲突、新旧快照、权限撤回
串行化、真实提交回滚、数据库权限/RLS 与延迟外键仍需隔离 PostgreSQL 单独验收。

## Dormant dedicated audit path

`StudioPublicationAudit` uses a fixed `studio_scene.publish` event and
`studio-publication-v1` tag on the connection proven by `StudioGuardedPublish.Authorized`.
The authorized proof is private to the operation callback and invalid after it returns.
The receipt checks the issuing adapter, same scope and complete expected record, including the
reserved audit ID; a mismatch poisons the owner's transaction. Neither the receipt nor its
validation is a commit acknowledgment. The transaction owner still must build the audit record from
the locked stream and accepted version's reserved ID, then require that receipt before moving the
pointer. No audit action was added to a live registry and no application privilege was granted.
