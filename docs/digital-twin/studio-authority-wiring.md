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
