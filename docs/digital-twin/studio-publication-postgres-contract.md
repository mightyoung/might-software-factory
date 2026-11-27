# Studio 发布存储：PostgreSQL 审查草案

[SQL 草案](studio-publication-postgres-draft.sql) **未执行，不是 Flyway 迁移，也没有运行角色授权**。
这里只保存已发布且不可变的 UTF-8 字节，当前版本是同租户、同屏幕的指针；Player 不查询任何草稿表。
SHA-256 是同一份规范化字节的指纹，不对 JSONB 的重新序列化结果计算。

## 写入顺序与参数

保持现有的业务命令锁顺序：会话解析租户和成员，建立一个顶层受管事务，先取得
`AuthorizationWriteGuard.forBusinessCommand(tenant)` 再查授权、项目启用及目标。参考
`LedgerRuntime` 的业务命令入口和 `JooqBusinessAuditAppend` 的同物理连接核验。
租户上下文在该连接上以事务局部方式设置；不同来源的租户/成员不进入 SQL。
已有 `ManagedIdentitySecurityTransaction` 拒绝嵌套事务且走全局安全变更锁，不可与业务命令
事务重叠开启或升级锁。真实接线需先选定同一业务事务的拥有者，在锁内从可信会话重新核验
发布权限并构造 Caller；应用层端口只借用该事务，而不是另开一次。不能用系统身份代替人，
不能用客户端角色、权限 epoch、actor 或时间，也不能用读权限替代发布权限。

1. 对授权后的 `(tenant, screen)`，绑定参数启动发布流：
   `INSERT INTO public.studio_publication_stream(tenant_id,screen_key,stream_id)
   VALUES (?,?,?) ON CONFLICT (tenant_id,screen_key) DO NOTHING`。stream UUID 由可信命令生成。
2. 另发 `SELECT stream_id,current_version FROM public.studio_publication_stream
   WHERE tenant_id=? AND screen_key=? FOR UPDATE`，恰一行。重复 INSERT 不是写内容；
   现有事务的发布头尚未创建也能竞争同一唯一键。取得锁后读取锁内头版本的摘要，不从
   曾读出的请求前快照判断。没有当前版本时仅接受 expectedVersion=0。
3. 先比较期望版本，再比较规范化摘要。旧版本即使内容相同也冲突。同头且摘要相同则无
   新版本、审计或指针更新。相异时版本只允许旧头 +1。绑定参数分别来自锁定目标、解码输出、
   可信发布成员、命令拥有者预分配的审计 ID、受管命令观察时间。写版本、现有统一审计
   SUCCESS 和指针更新都在同一物理事务。指针更新使用
   `WHERE tenant_id=? AND screen_key=? AND current_version IS NOT DISTINCT FROM CAST(? AS bigint)`；
   旧头参数首次为 NULL，必须恰改一行。成功只能在提交后返回。
4. 审计必须匹配真实 tenant、actor、studio publish 动作、stream UUID、命令/链路和可信时间，
   不保存 Studio 载荷、外链或凭据。延迟审计外键允许应用端口当前 append→audit 调用次序，
   并在提交时发现缺失审计；外键只证明同租户有该 ID，不验证动作/主体或提供授权。
   未来先审核动作注册和既有审计适配能力：`BusinessAuditRecord` 接受预分配 ID，
   但当前 `JooqBusinessAuditAppend` 的 action/change-kind 约束与策略标签仍属现有业务，
   **不能假定其已经支持 Studio publish**。

## 只读查询

- 固定版：`SELECT ... FROM public.studio_published_version
  WHERE tenant_id=? AND screen_key=? AND version=?`，不存在就返回无记录，不降级到当前版。
- 当前版：用单条语句以 `v.tenant_id=s.tenant_id AND v.screen_key=s.screen_key
  AND v.version=s.current_version` 连接该租户与屏幕的 stream 和 version，未发布则无记录。
- 两路都先核可信会话及 `PLAYER_READ`；不能因知道摘要或 ETag 命中而省略核权。
  显式复核返回的 target/digest/bytes；不返回 actor、内部 stream/audit UUID 或当前头给拒绝请求。
- 未来列级授权须单独审查，不授历史 UPDATE/DELETE/TRUNCATE、DDL、对象拥有权或越过 RLS。
  触发器不能代替最小权限、可信连接与按租户查询。

## 真实验证尚缺

使用一次性 PostgreSQL，用独立连接证明两次同时首次发布只有一条历史和审计、已有头的
版本竞争只有一方成功、锁等待期间权限撤回不会复用旧决策、审计失败/延迟外键失败会
同时回滚版本与指针。再验证相同 screen 的异租户、错误租户外键、固定版本仍原样返回、
历史不可改、无权不查询、无当前版本 fallback。测试应观察真提交而非仅事务回调结束；
不把此草案或已有 fake 单元测试称为数据库验收。
