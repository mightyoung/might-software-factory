# D0 运行权限收敛清单

状态：operator 脚本与离线契约检查已实现；实际 PostgreSQL 权限和命令验收由 D0 运行记录另行提供。本文件不宣称脚本已在任何数据库执行。

## 适用范围

[d0-runtime-grants.sql](../backend/src/main/resources/db/operator/d0-runtime-grants.sql) 管理 V19–V25 新增的 **42 张表**。旧记录中的“41 张新表”需要以目标库清单核对，不能作为静态完整表数。本脚本不修改迁移文件、PUBLIC/default ACL、角色属性或其他成员授权，也不提供 V2–V18、Spring Session、Flowable、Modulith 的完整基础授权。

仅允许明确的新开发库 `might_software_factory_dev_20261115` 或命名为 `msf_d0_*_test` 的隔离库。运行者必须单独传入当前库名、运行角色和确认字符串。运行角色必须是可登录、非超级用户、非 BYPASSRLS、无建库/建角色/复制权限的角色，不能拥有 public 中的表/函数或继承其他角色；不满足则整笔回滚。

```sh
psql -X -v ON_ERROR_STOP=1 \
  -v target_database=msf_d0_core_test \
  -v runtime_role=msf_d0_runtime \
  -v d0_ack=d0-runtime-direct-grants-v1 \
  -f backend/src/main/resources/db/operator/d0-runtime-grants.sql
```

连接信息通过私有连接配置提供，不放入脚本或命令日志。先应用当前迁移、框架 schema 与旧模块最小授权，再运行本脚本。V23 仍沿用独立超级账号迁移及恢复函数属主的既有流程。

## 权限按实际实现声明

| 模块 | 表与权限 | 来源 |
|---|---|---|
| V19 身份生命周期，6 表 | 挑战 INSERT 与 state/version/consumed_receipt_id UPDATE；限流 INSERT 与 attempts UPDATE；回执、投递尝试、事件只追加；恢复联系人 INSERT，另有 UPDATE(version) 用于 FOR UPDATE | JooqIdentityLifecycleStore、JooqIdentityLifecycleAudit；V19 invoker 约束 |
| V20 会话与敏感动作，9 表 | account_session_authority INSERT 与撤销列 UPDATE；proof 只允许消费状态列 UPDATE；会话命令回执和敏感事件只追加；activation 只读 | JooqSessionAuthorityStore、SessionAuthoritySql、JooqSessionSensitiveAudit |
| V20 其余声明 | reauth issuance、recovery change/receipt/delivery 当前没有主代码写入实现，因此不给 INSERT/UPDATE | 对当前主代码写 SQL 的核查；未来实现必须追加单独权限与验证 |
| V21 资产基础，19 表 | 根/关联表只允许实际 INSERT 列与 UPDATE 列；修订和命令回执只追加；5 个父根增加 interval_mutex_revision UPDATE | AssetFoundationSql、V21 g014_fence_parent；历史与回执不可改 |
| V22 风险，6 表 | 实际 INSERT/UPDATE 列；修订、快照与回执只追加 | RiskScenarioSql 与已实库验证的 ci-pg18-source/runtime-grants.sql |
| V24 原因，1 表 | INSERT 五列，无 UPDATE/DELETE | 持久消费者与原 cause 只追加约束 |
| V25 权限配置，1 表 | permission_change_event INSERT，无 UPDATE/DELETE | JooqPermissionAdminRepository；配置变更历史只追加 |

42 表的 SELECT 明确列举当前列，供实际读取和 SECURITY INVOKER 约束中的 `SELECT *` 使用；未发布的新列不会自动获得权限。写入列来自当前 producer SQL，历史、修订与回执不授 UPDATE，全部表不授 DELETE/TRUNCATE/REFERENCES/TRIGGER/MAINTAIN。

受管表先撤销指定运行角色的旧表级与旧列级授权，再按清单授予列权限；一次事务内完成，检查每个列的 effective SELECT/INSERT/UPDATE/REFERENCES 以及全部表级权利。若 PUBLIC 或其他未审查来源造成额外权限，脚本失败并回滚，不尝试删除共享授权。

旧表仅增加已实现身份邀请所需的 INSERT 列：account、principal、credential、tenant_member；tenant_member 的 `FOR UPDATE` 需要 UPDATE(auth_epoch)，没有新增角色/范围/组织写入。权限配置仍需 role_permission 精确四列 INSERT 及行 DELETE。已有改密所需的 account UPDATE(credential_version) 和 credential UPDATE(secret_hash,changed_at)、基础读取等由旧模块授权基线保留，本脚本不重写。

## 函数调用和行锁

V21/V22 已撤销 PUBLIC EXECUTE 的普通 invoker helper 共 **32 个签名**逐项列举，不授 `ALL FUNCTIONS`，不为 trigger-returning 函数增加执行权限。V19/V20 的既有 PUBLIC 执行基线不在本次修改范围。函数本身仍由迁移身份拥有，发现 SECURITY DEFINER 或触发器返回类型即拒绝。

风险源已有精确 22 函数授权（含 V21 两个 CHECK predicate），本脚本合并 V21 资产路径所需普通 helper，去重后 32 个。`g014_fence_parent` 实际更新父根 `interval_mutex_revision`，所以单纯授业务 UPDATE 列不够。PostgreSQL `SELECT FOR UPDATE` 也要求至少一列 UPDATE，因此恢复联系人提供 version 列，邀请成员检查提供 auth_epoch 列；它们仍受业务事务与触发器约束，不以行锁为理由授整表 UPDATE。

## 基础模块来源及验证边界

旧模块权限规则集中在 `IdentityLoginPostgresIT.RuntimePrivilegeProfile`：`business-authentication-audit` 包含业务、改密和认证事件权限；它是测试环境 effective-rights 的验证规则，并非可直接执行的完整授权脚本。上传/扫描的新增路径还需对照 FindingEvidenceUploadIntentPostgresIT 与 IngestionFinalizationPostgresIT。CI capture/source/recovery 脚本分别只支持其固定路径，不能拿其中一条充当完整应用授权。

[D0RuntimePrivilegesTest](../backend/src/test/java/com/mightyoung/softwarefactory/D0RuntimePrivilegesTest.java) 比较新表集合、当前 producer INSERT/UPDATE 列、只追加限制、父根 fence 列及 migration 中撤销的普通函数集合。它是遗漏检测，不能证明真实角色、RLS、延迟触发器和框架 SQL 均可运行。D0 出口仍须用独立 runtime 实际启动、登录、读写、权限变更及失败回滚验证；重复执行本脚本并观察相同 effective ACL。

### 上传技术状态补充

旧模块的 `d0-legacy-runtime-grants.sql` 对 upload intent 只授技术状态列 UPDATE。`JooqFindingEvidenceIngestionRepository.persistChange`（127–132 行）统一用于领取、租约、重试与终结状态，实际还写入 `scanner_generation_sha256`；该列已补入，避免整条 UPDATE 因缺列权限返回 42501。声明身份及文件归属列保持不可写。[D0RuntimeIngestionPrivilegesTest](../backend/src/test/java/com/mightyoung/softwarefactory/D0RuntimeIngestionPrivilegesTest.java) 从真实 worker SQL 提取所有赋值列，与该表清单做完全相等检查，防止后续技术列再次遗漏或意外扩大授权。

### 文件元数据行锁补充

三个实际读取适配器使用 `FOR SHARE`：JooqFindingEvidenceContentReadRepository（102 行）锁 file_link 与 file_object，JooqEvidenceFileReferenceReadRepository（49 行）锁 file_link，JooqFileEvidenceMetadataReadRepository（28 行）锁 file_object。PostgreSQL 要求这两表具有至少一列 UPDATE。legacy 脚本仅为两表授 `UPDATE(tenant_id)`；V6 的 reject_file_link_rebinding 与 reject_file_object_submission_version_rewrite 均拒绝 tenant_id 变更，因而不能用这项列权限修改归属、状态、摘要或存储版本。脚本预先核验对应身份触发器已启用，再添加锁用列；没有其他 file 元数据列 UPDATE。[D0RuntimeFileLockPrivilegesTest](../backend/src/test/java/com/mightyoung/softwarefactory/D0RuntimeFileLockPrivilegesTest.java) 对照三条真实锁查询及迁移身份守卫。

### 权限关联父表锁补充

V23 的 `guard_audit_query_role_permission`（252、256 行）在插入/更新 role_permission 时对 permission、scope_rule 执行 `FOR KEY SHARE`，同样要求每个父表至少一列 UPDATE。经本轮架构审阅，D0 delta 精确增加 `permission UPDATE(label)` 与 `scope_rule UPDATE(version_no)`；legacy 基线仍只读这两个父表，应用 delta 后逐列核验最终 effective UPDATE 只能命中各自该一列，整表 UPDATE 或其他语义列 UPDATE 均使授权事务失败。

这两项是**实际可写的元数据**，不是不可变或无副作用的“锁占位列”：拥有数据库连接的 runtime 可以修改展示标签或 scope 版本数字。V3 只对 version_no 约束 `>=1`；当前授权读取不使用 version_no，权限码、动作、范围种类、definition 和对象身份保持不可写。该边界解决当前 invoker 父锁前提，不表示提供了在线范围编辑能力。未来若 scope 版本参与授权/缓存语义，必须重新评估列权限，不能沿用本例推断安全。

[D0RuntimePermissionParentLocksTest](../backend/src/test/java/com/mightyoung/softwarefactory/D0RuntimePermissionParentLocksTest.java) 对照实际配置写入、V23 两条父锁、精确 grant 和最终 effective 检查，并锁定当前 scope 版本未参与授权的前提。本修复不修改历史迁移、不使用 SECURITY DEFINER，也不向 runtime 开放父表其他列。
