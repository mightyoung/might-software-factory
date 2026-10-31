# 隐患复核确认输入与身份隔离契约

状态：确认意见/等级和身份隔离实现在独立集成分支；“不成立”关联新建契约仍是设计，尚未加入 API/DB/BPMN 运行时。
基线：远端 `dev` `61c83651b1a35340ef814524095686ae784d0d37`。本切片同步更新 OpenAPI；部署前仍须盘点旧客户端和历史幂等回执。

## 已批准的规则

- PERSON 和 SAFETY 阶段分别必须有有效确认人，且两阶段成员 ID 不同。
- 两位确认人都不能是当前上报人。
- 后端每次确认都从认证会话取得当前成员；不接受请求体的 actor/member 覆盖。
- 安全确认不能由已完成 PERSON 确认的同一成员提交，即使流程任务后来被错误地指派给该成员。
- 缺少指派、两阶段被指派给同一成员，或上报人被指派为任一确认人，均阻断创建/流程初始化并返回现有 422 `WORKFLOW_RULE`。待办不跳阶段，不创建空确认或启动错误的流程实例。
- 安全阶段要求找不到先前 PERSON 确认历史时拒绝继续；不以状态字段推定第一确认已经发生。
- 被拒绝的确认不变更状态/版本、不追加确认/转移/审计、不完成流程任务；确认的身份与历史检查先于幂等回执领取。创建/旧记录初始化中的无效确认路由在领取回执后检查，但异常会回滚事务中的回执和业务写入。
- 既有的租户/当前权限检查、版本比较和任务 assignee 校验继续生效。当前 Flowable 适配器会校验正在完成的 task actor 与 assignee 一致。
- PERSON 确认请求为 `{ expectedVersion, reason }`；SAFETY 确认请求为 `{ expectedVersion, reason, grade }`。两者 `reason` 去首尾空白后必须非空，原始 JSON 字符串长度最多 4000 个 Java 字符单元；SAFETY 的 `grade` 只能是 `GENERAL` 或 `MAJOR`，PERSON 请求拒绝 `grade` 等未知字段。
- 确认意见写入确认记录、状态转移和审计；SAFETY 意见及等级也写入工作流审计与 finding 等级。意见/等级和阶段/版本进入确认命令指纹版本 `confirmation-payload-v2`；相同键重放同一完整请求返回既有结果，更改内容返回 409 `IDEMPOTENCY_CONFLICT`。
- 缺少必填意见、无效等级或缺少等级返回 400 `INVALID_REQUEST`；确认请求带未知字段返回 400 `INVALID_QUERY`。这类输入错误在事务及领取幂等回执前拒绝。

## “不成立”与退回补充的分离契约

1. 复核阶段选择 `NOT_ESTABLISHED` 时，目标记录进入单独终态 `NOT_ESTABLISHED`；必须保存非空意见、执行成员、阶段、时间、命令 ID 和状态审计。
2. `NOT_ESTABLISHED` 不允许任何命令直接重开、回退或改写成待确认。现有整改验收通过的 `CLOSED` 也不作“不成立”解释。
3. 收到新证据时创建新的 finding，并向旧的 `NOT_ESTABLISHED` 记录建立有租户作用域的关联。推荐在创建请求加入可选 `relatedFindingId`；服务端验证目标可见且处于 `NOT_ESTABLISHED`，之后生成新 ID/编号、独立描述/证据/确认链/审计，原记录不改。
4. 若一条新报告只允许关联一条旧记录，可用 `finding.related_finding_id` 及同租户复合外键、自关联 CHECK 和不可变关系保护。若必须关联多条历史记录，则改用 `finding_relation(tenant_id, source_finding_id, related_finding_id, relation_type, created_at, created_by)`；不要把多个 ID 塞进 JSON。链接类型建议限定为 `NEW_EVIDENCE_AFTER_NOT_ESTABLISHED`。
5. 目标不存在或跨租户时使用通用 404 `RESOURCE_NOT_VISIBLE`；目标可见但不是 `NOT_ESTABLISHED` 时返回 422 `WORKFLOW_RULE`，不把新报告合并到原记录。
6. `RETURN_FOR_INFORMATION` 是非终态：进入 `REPORTER_SUPPLEMENT_PENDING`，上报人追加材料后回到 PERSON 重新走两级复核。它不能复用 `NOT_ESTABLISHED`，补充也不能被渲染成关闭理由。

## 实现边界与版本兼容

本切片不新增迁移、BPMN 或状态值。现有 V2 的状态 CHECK 不含 `NOT_ESTABLISHED`；现有 V1 BPMN 只有正常确认/整改/验收路径。Flowable 运行版本在根 `pom.xml` 固定为 8.0.0；工作流绑定保存精确的 `definition_id`，完成任务时也校验实例定义 ID 与绑定相同。因此仅部署新 BPMN 不会自动把在途 V1 实例迁到新路径。新增状态/关系必须等追加迁移、V2 流程和针对运行实例的迁移/阻断策略都通过集成测试后再启用。

兼容发布门槛：旧确认请求若缺少 `reason` 或 SAFETY `grade` 会被拒绝（400）；PERSON 请求若发送等级等未知字段也会被拒绝（400）。旧幂等回执使用旧请求指纹；同一旧键配新完整请求可能返回 409 `IDEMPOTENCY_CONFLICT`，本切片不重写旧回执，也没有 V1 透明重放桥接。发布前须核实实际旧客户端与未过期回执；若仍需无感重放，先实现并测试兼容方案再部署。`confirmation-payload-v2` 不代表整个 API schemaVersion 改版。

没有连接、改写或应用 NAS/数据库迁移；当前变化仅为代码与契约，不能据此声称真实 PostgreSQL/Flowable 流程验收通过。

## 当前测试覆盖与后续契约验收

- 已覆盖：重复/不合格确认人路由、上报人自审、跨租户 PERSON 历史隔离、SAFETY 缺 PERSON 历史、确认意见/等级校验与持久化、同键重放及改动意见/等级后的幂等冲突、OpenAPI 请求 schema 和稳定 HTTP 错误映射。
- 仍待开发验收：`NOT_ESTABLISHED` 后的终态限制与新证据关联；`RETURN_FOR_INFORMATION` 的上报人补充、复核周期、状态和流程迁移。
