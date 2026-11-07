# 隐患派单候选成员接口

`GET /api/v1/findings/{id}/dispatch-candidates` 为后续前端选择提供当前隐患组织内具备整改资格的成员。权威契约见 [Finding Workflow OpenAPI](../contracts/openapi/finding-workflow.yaml)，验收见[本批记录](verification/dispatch-candidates-sealing-2026-11-03.md)。

## 请求和分页

接口使用服务端 Session，从服务端解析租户、调用者和隐患组织，不接受客户端指定身份、组织或权限。未知、重复查询参数返回 400。

|参数|规则|
|---|---|
|`id`|完整规范 UUID，接受大写十六进制|
|`q`|可选，区分大小写的显示名前缀；裁剪两端空白前最多 100 个 Unicode 码点；拒绝控制字符和非法代理项；`%`、`_`、反斜杠按字面匹配|
|`limit`|默认 20，范围 1–50，正十进制整数，不接受前导零|
|`afterMemberId`|可选，上一页最后一个已返回候选的 UUID|

响应包含六个顶层字段：`data`、`findingVersion`、`roundNo`、`state`、`hasMore`、`nextAfterMemberId`。每个候选只包含 `memberId` 和 `displayName`。

资格与前缀过滤在分页前应用；按成员 UUID 升序读取最多 `limit + 1` 条，用额外一条判断下一页。游标取本页最后一个实际返回的合格成员，否则为 null。不返回总人数、账号、principal ID 或不合格成员的游标。每次请求重新读取授权与数据，页面之间不共享固定快照；成员或授权变化可能影响后续页面。响应为 `Cache-Control: no-store`。

## 权限和状态

调用者须具有当前有效会话、安全应用访问权和目标隐患组织的 `finding.dispatch` 动作范围。仅初始 `SAFETY_DISPATCH_PENDING`，或 `RECTIFYING` 且轮次大于 1、当前轮尚无整改通知的返工阶段允许查询。

候选必须同时满足：

- 租户、成员、账号与 HUMAN principal 有效。
- 直接属于有效的隐患组织，组织成员关系在有效期内。
- 通过 MEMBER、ORG、POSITION 或 GROUP 来源获得有效角色授权；来源组织、岗位、群组及其关系当前有效。ORG 来源可以是成员的其他有效组织。
- 角色有效，许可精确匹配 `finding.remediate`、资源 `finding`、动作 `remediate`。
- 范围为 DEPT、DEPT_TREE，或包含隐患组织的 EXPLICIT；SELF、RELATION 不提供该指派资格。

有效期使用 `valid_from <= current_timestamp`，且 `valid_to` 为空或大于当前时间。指派中的 DEPT_TREE 可以简化，因为直接目标组织成员关系已包含该组织；调用者的一般动作范围解析保持原规则。

候选检索与派单写路径的单成员校验共用授权仓库的完整 SQL 资格谓词，以集合查询完成，不逐成员查询授权。候选仅是当次读取的提示，不证明任务归属或授予派单权。实际派单仍校验当前权限、隐患版本、成员资格和安全派单任务归属。

## 事务和错误

隐患、调用者动作范围、候选在同一个顶层只读 REPEATABLE READ 事务中解析，不获取命令授权 advisory lock 或业务行写锁。PostgreSQL 逐语句超时默认 2 秒，可通过 `software-factory.findings.dispatch-candidates.statement-timeout` 设置为 1 毫秒至 5 秒；Spring 事务超时设为 5 秒。这些设置不构成整个 HTTP 请求精确 5 秒硬截止的保证。

|状态|含义|
|---|---|
|400|参数无效|
|401|缺少有效会话|
|403|身份、应用或派单动作权限不可用|
|404|隐患不存在、跨租户或超出动作范围|
|409|当前未等待派单|
|503|数据库或读取超时等依赖故障，未返回完整候选|

前端“分派整改”仍禁用，尚未消费此接口，禁用说明中仍有旧文案。PR #16 本批未处理。本接口不创建证据集、不上传或扫描文件，也不改变整改提交与封存规则。
