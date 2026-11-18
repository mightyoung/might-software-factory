# 可配置功能权限与风险管控首批竖切设计

状态：设计，2026-11-10。依据：用户要求(1)后端权限改为可配置，新增「系统设置-权限配置」；(2)按“泛化导航授权→风险管控只读台账→排查/设备/综合管理”顺序分批开发。

## 一、现状（读源码与实库所得）

- 授权数据已是数据驱动：`permission`(全局目录) → `role_permission`(租户内，带 `scope_rule_id`) → `role_grant`(角色授予成员/组织/岗位/用户组)。`JooqRoleScopeReadRepository` 按权限码解析有效范围，逐请求读库，不缓存在会话里。
- 写死的部分只有展示层与入口：`NavigationService` 固定应用 `safety`、唯一菜单节点 `finding.read`；`PersistentEntityReadAccessPolicy` 只认 `finding` 实体。
- 运行账号对授权表只有 SELECT；迁移账号 `msf_dev_migrator_20261107` 拥有全部权限；运行时不执行 DDL。
- 现有 `role_permission` 的范围规则均为 EXPLICIT(绑定组织)，每个角色一条，该角色的所有权限共用。

## 二、权限模型与配置

1. `permission` 增加列 `application_key`、`label`、`sort_order`（加列，不改既有行语义）；新增权限码：
   `risk.read`、`inspection.read`、`equipment.read`、`platform.permission.read`、`platform.permission.manage`。权限码按首个 `.` 拆为 resource_type 与 action，与既有种子一致。
2. 菜单目录 `MenuCatalog` 写在代码中（节点→应用、路径、所需权限码），**节点是否可见由库里角色权限决定**，不再写死。应用入口 = 租户已启用该应用 且 至少一个节点可见。
3. 权限配置能力（应用 `platform`，路径 `platform/permissions`）：
   - 读：`GET /api/v1/admin/permissions`(权限目录)、`GET /api/v1/admin/roles`(角色、已配权限、成员数)；需 `platform.permission.read`。
   - 写：`PUT /api/v1/admin/roles/{roleId}/permissions`，整体替换该角色权限集合，带 `expectedVersion`(角色当前权限集合的哈希)做乐观并发；需 `platform.permission.manage`。
   - 范围规则沿用该角色已有的 `scope_rule`；角色当前没有任何权限（无可继承范围）时拒绝并说明，v1 不在界面创建范围规则。
   - 保护：不得让租户失去最后一个持有 `platform.permission.manage` 的有效角色；不得写入目录之外的权限码。
   - 写事务走 `ManagedIdentitySecurityTransaction`（全局安全写锁）；每次变更写 `permission_change_event` 追加表（谁、何时、角色、增删的权限码）。
   - 权限逐请求读库，变更即时生效，不需要 bump `auth_epoch`。
4. 运行账号新增最小授权：`role_permission` 的 INSERT/DELETE，`permission_change_event` 的 INSERT/SELECT。`permission`、`role`、`scope_rule` 仍只读。行级安全沿用 `app.tenant_id`。

## 三、风险管控只读台账（首批）

- 表（迁移 V20，均带 tenant_id、行级安全、运行账号只 SELECT）：`hazard_source`、`risk_scenario`、`risk_hazard`、`risk_assessment`、`control_measure`、`risk_control`，字段取自 `logical-schema.md` 第3节；缺评估时评估等级为空，界面显示“待评估”，不默认低风险。
- 接口：`GET /api/v1/risk/scenarios`(分页、按等级/状态筛选)、`GET /api/v1/risk/scenarios/{id}`(危险源、评估历史、措施及责任)。权限 `risk.read`，范围规则同其他读权限（组织范围）。
- 写操作不在本批。样例数据只放开发库种子脚本，明确标注“联调样例”。

## 四、前端

- 菜单与应用入口完全来自 `bootstrap/menu`；`App.tsx` 路由表中的权限映射改为读服务端返回的权限码，未知权限不授予。
- 新页：`platform/permissions`（角色列表、权限勾选、差异预览、保存、冲突提示）；`safety/risks`（台账表 + 详情抽屉）。
- 演示模式提供合成适配器；HTTP 模式不回退合成数据。

## 五、验证

- 后端：授权/导航单元测试、模块边界测试、迁移契约测试；实库针对 devtest 租户的接口联调。
- 前端：typecheck、vitest、浏览器按 4 个测试账号走权限配置→菜单变化→风险台账。
- 不宣称：生产迁移、多租户并发压力、完整排查/设备/综合管理（后续批次）。

## 六、后续批次

排查任务、设备管理、综合管理各自走“契约→迁移→接口→页面”，复用本批的菜单目录与权限配置。

## 七、实现状态（2026-11-10，本地开发库）

- 已实现并联调：V19 权限目录与变更日志、导航/授权按库内角色权限驱动、`/api/v1/admin/*` 权限配置（读/整体替换/并发校验/末位管理员保护/审计）、权限配置页；V20 风险管控表、`/api/v1/risk/scenarios` 只读接口、风险台账页。
- 验证：后端 980 项离线测试、前端 395 项、构建与体积预算通过；对本地开发库做了接口联调（增删权限、版本冲突、越权、末位管理员、变更即时影响菜单）和浏览器核对。样例数据见 `backend/src/main/resources/db/dev-seed/`，仅开发库。
- 迁移执行：Flyway 经迁移账号（`SET ROLE`）应用 V19/V20；运行账号授权见 `db/operator/`，须由运维单独执行，未并入 Flyway。
- 未做：权限配置不创建范围规则，不管理“成员—角色”授予；风险管控无写入、无与隐患关联；排查任务、设备管理、综合管理仍是占位；演示模式的风险数据为合成。真实 Secure Cookie/HTTPS、生产迁移、并发压测未验证。

## 八、排查任务只读台账（2026-11-10）

- V21：`inspection_task`、`inspection_item`、`inspection_result`（行级安全，运行账号只读，授权见 `db/operator/V21-runtime-grants.sql`）。检查项直接挂在任务下，未做检查表版本化。
- 接口：`GET /api/v1/inspection/tasks`（状态/关键词/分页，状态含计算出的 `OVERDUE`）、`GET /api/v1/inspection/tasks/{id}`；权限 `inspection.read`，范围按组织。无结果的检查项 `result=null` 显示“未检查”，不等于通过。
- 样例：`db/dev-seed/V21-devtest-inspection-samples.sql`（仅开发库）。
- 未做：任务生成/分派/填报结果/由不合格项转隐患、检查表版本、证据上传。
