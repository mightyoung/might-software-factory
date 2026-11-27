# Studio 权限接线：开发规格，未注册

已确定的产品规则：租户内场景管理员发布；Player 仅查看当前租户内单独授权的屏幕。
两项能力互不继承、默认拒绝，现有账号不改变。下列名字只是建议，不注册、不分配。

## 为什么不能直接接现有授权

- `EffectivePermissions.has` 在 `PersistentEffectivePermissions` 中实际是可读组织集合
  非空；它既不证明租户级发布，也不能表达单块屏幕。`ApplicationActionAuthorization.Decision`
  也只携带组织集合；`ApplicationEntityAction` 的动词检查还排除了 publish。
  不把组织 UUID、租户管理员、台账配置 publish 或通用 read 当替代。
- V23 对 `scope_rule.kind` 列的是显式白名单，已有 `IDENTITY_TENANT`、`AUDIT_TENANT`
  都各自锁定特定权限；不能把 Studio 放进去。按该专用能力模式，拟用
  `studio_scene.publish` + `STUDIO_TENANT`、`studio_screen.read` + `STUDIO_SCREEN`。
  两种配对分别受限，禁止相互配对、空/通配资源或自动角色继承。真实读授权必须把屏幕
  绑定在同租户下：如果未来使用 UUID 作为 scope 目标，先有独立的受管屏幕身份映射
  `(tenant_id, screen_key)`，不能借用发布才创建的 stream 判断是否存在。
- `CheckedHumanSessionAuthority` 已提供绑定同一回调和边界的会话核验；它只核会话，
  不提供业务能力。两个专用能力的当前查询、同物理事务的 SQL 边界、屏幕身份关系
  仍不存在。先实现这些真正前置条件，再接已存在的 `StudioPublicationPolicy.Caller`；
  此时加一个只调用虚构授权端口的 wrapper 不会验证真实权限。

## 适配器先写测试，再接线

1. 发布：服务端已验证的会话 `CurrentTenantContext` 提供 tenant、actor、member；
   顶层事务局部绑定该租户，持 `AuthorizationWriteGuard.forBusinessCommand` 后调用
   `HumanSessionAuthorityTransaction.withGuarded`、`requireGuardedCheckedCurrent`。
   在同一物理连接核验仅 tenant 发布能力；否决前不解码、不看屏幕或发布表。
   授权后传同 scope 身份进现有服务，在该事务完成版本/审计/指针并提交。
2. Player：事务中 `withRead`、`requireReadCheckedCurrent` 后校验目标租户等于会话
   当前租户，按 canonical screen key 核专用当前授权。权限判断查询只查授权绑定，
   不查发布头；未知、未授、异租户同名屏统一拒绝。允许后，同一已核连接读 pin 或 alias。
3. recorder 固定 actor/account/member 为不同 UUID，夹具 A、B 同名屏，A 屏 1 已授、
   屏 2 未授，A 发布者无读授权。断言事务 guard → 当前会话 → 专用能力 → 目标存储；
   模拟 capture 后撤权，guard 内返回否决，验证零解码/零发布存储调用；
   模拟回调 scope 或连接交换并验证拒绝。Player 对 A 屏 1 的别租户同名、
   A 屏 2 与无 session 返回同类否决；授权发布不能给 Player 绕过读取。
   已提供的 JUnit 19 项只验证领域边界，不能代替以上适配器测试。

后续专用动作注册、scope 持久化、权威查询及授权变更串行化需成对评审。本文件没有
可执行迁移、测试数据库、授权授予、路由或部署。

## 单独评审的物理方案

[SQL 草案](studio-authority-postgres-draft.sql) 用两张专用、正向的租户成员关系表分别存储
场景管理员和单屏读取，并以第三张表登记与发布流独立的屏幕身份。它是拟议的**唯一权限来源**，
不是与现有角色或 V23 叠加的第二条放行路径；上面的能力/范围名只用于描述语义。
未来若选择扩展通用权限模型，需以等价强约束替换此存储，不同时查询两套表。这里不改 V23，
不定义谁可以分配上述授权，也不设计开放的写权限。

- 发布在 guard 内按会话的 `(tenant_id, member_id)` 验证管理员记录；允许后才验证
  该租户的 screen 存在且未退休，再读/建发布流。读取用同租户、成员、精确 screen key
  授权关系连接未退休 screen，在查询发布表之前完成；未知/退休/未授权都返回同一拒绝。
- key 是稳定身份：注册后不改 key、不删除重建、不复用退休 key；显示名若需要另建字段。
  退休必须使新读/写拒绝，但历史版本仍保留。草案用外键阻止有引用时删改，
  **不单靠 DDL 保证**无引用时的 key 不可改，也不代替未来应用角色、列权限或维护约束。
- 分配/撤销、注册/退休走现有授权变更的**全局排他** guard，锁后重新核验人类身份和管理
  权限，同一事务更新关系并审计；还需决定哪些变化按现有安全写入惯例推进相应 epoch。
  发布使用与之冲突的业务 guard 后再核授权。若要求撤权与在途 Player 读严格串行，
  Player 也必须在其受管事务取得兼容的共享授权 guard（不能把现有普通读边界当作持锁）；
  先核撤权确定先后关系，再在同连接读取 pin/alias。不得只依赖外键、RLS、缓存或捕获前判定。
- 此文件未执行。真实通过仍需验证身份不可复用、退休和撤权两种锁顺序、RLS/事务约束，
  并独立审查管理命令与运行角色；无 seeds、GRANT、路由或运行时绑定。

[托管只读探针草案](studio-authority-pg18-probe-draft.sql) 固定两个合成租户、同名
`floor` 屏幕，另在 A 注册未授的 `other`，为 A/B 各配置不同成员的 `floor` 读授权，
A 另有只发布管理员；没有发布流。探针拒绝非指定回环 PG18/库名/非所有者角色，并明确
校验当前角色仍强制受 RLS。会话 ACK 只是额外信号，**不是所有权证据**。未来须先为
`CiPg18EphemeralTarget` 实现独立 Studio lane，复用其他 lane 的受保护容器回执和服务端
实例标记核验；检测失败应在任何 DDL 前终止。只有经批准的专有 bootstrap 才能装草案、
创建合成夹具并提供只读角色；本草案不包含初始化、DDL 执行器、授权、Maven profile 或
工作流。它只测试关系和 RLS，不证明受管会话、撤权锁、真实应用事务或发布功能。

`StudioAuthorityQueries` 是包内、未接线的 JDBC 查询实现：只消费受管调用者传入的当前
上下文和物理连接，执行固定 tenant/member 或 tenant/member/screen 查询。
它不颁发 `Caller` 或自证连接所有权，不设置租户，不关闭连接，不在异常时退回通用权限。
接线仍必须由真实操作拥有者先核对人类会话、活跃身份与 epoch，再调用
`requireGuarded` / `requireRead` 认证该物理连接；需要撤权串行时先持对应 guard。

`StudioGuardedPublish` 接上了现有受管**发布**边界，仍未注册：它只借用已绑定的
顶层连接，由 `withGuarded` / `requireGuarded` 核真实业务 guard 与连接，并要求
`requireGuardedCheckedCurrent` 返回 ENFORCING 后才查场景管理员关系、调用同步发布回调。
事务结束与发布结果的提交由外层拥有者负责。现有普通 `withRead` 需要只读
REPEATABLE READ，而现有业务 guard 需要 READ COMMITTED 写事务，不能把两者拼成
具备严格撤权串行的 Player 路径。该读路径仍未接线。
