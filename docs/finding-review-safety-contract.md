# 隐患复核身份隔离与“不成立”记录契约

状态：身份隔离已实现于本地分支；“不成立”关联新建契约为设计定稿，尚未加入 API/DB/BPMN 运行时。
基线：`ba200f73ad6f9901224eb9e6f2dfa7b75ae75413`。本文件不修改 `contracts/openapi/*` 或前端生成类型，避免与主实施线程冲突。

## 已批准的规则

- PERSON 和 SAFETY 阶段分别必须有有效确认人，且两阶段成员 ID 不同。
- 两位确认人都不能是当前上报人。
- 后端每次确认都从认证会话取得当前成员；不接受请求体的 actor/member 覆盖。
- 安全确认不能由已完成 PERSON 确认的同一成员提交，即使流程任务后来被错误地指派给该成员。
- 缺少指派、两阶段被指派给同一成员，或上报人被指派为任一确认人，均阻断创建/流程初始化并返回现有 422 `WORKFLOW_RULE`。待办不跳阶段，不创建空确认或启动错误的流程实例。
- 安全阶段要求找不到先前 PERSON 确认历史时拒绝继续；不以状态字段推定第一确认已经发生。
- 被拒绝的确认不变更状态/版本、不追加确认/转移/审计、不完成流程任务；确认的身份与历史检查先于幂等回执领取。创建/旧记录初始化中的无效确认路由在领取回执后检查，但异常会回滚事务中的回执和业务写入。
- 既有的租户/当前权限检查、版本比较和任务 assignee 校验继续生效。当前 Flowable 适配器会校验正在完成的 task actor 与 assignee 一致。

## “不成立”与退回补充的分离契约

1. 复核阶段选择 `NOT_ESTABLISHED` 时，目标记录进入单独终态 `NOT_ESTABLISHED`；必须保存非空意见、执行成员、阶段、时间、命令 ID 和状态审计。
2. `NOT_ESTABLISHED` 不允许任何命令直接重开、回退或改写成待确认。现有整改验收通过的 `CLOSED` 也不作“不成立”解释。
3. 收到新证据时创建新的 finding，并向旧的 `NOT_ESTABLISHED` 记录建立有租户作用域的关联。推荐在创建请求加入可选 `relatedFindingId`；服务端验证目标可见且处于 `NOT_ESTABLISHED`，之后生成新 ID/编号、独立描述/证据/确认链/审计，原记录不改。
4. 若一条新报告只允许关联一条旧记录，可用 `finding.related_finding_id` 及同租户复合外键、自关联 CHECK 和不可变关系保护。若必须关联多条历史记录，则改用 `finding_relation(tenant_id, source_finding_id, related_finding_id, relation_type, created_at, created_by)`；不要把多个 ID 塞进 JSON。链接类型建议限定为 `NEW_EVIDENCE_AFTER_NOT_ESTABLISHED`。
5. 目标不存在或跨租户时使用通用 404 `RESOURCE_NOT_VISIBLE`；目标可见但不是 `NOT_ESTABLISHED` 时返回 422 `WORKFLOW_RULE`，不把新报告合并到原记录。
6. `RETURN_FOR_INFORMATION` 是非终态：进入 `REPORTER_SUPPLEMENT_PENDING`，上报人追加材料后回到 PERSON 重新走两级复核。它不能复用 `NOT_ESTABLISHED`，补充也不能被渲染成关闭理由。

## 实现边界与版本兼容

本切片只实现身份隔离。现有 V2 的状态 CHECK 不含 `NOT_ESTABLISHED`；现有 V1 BPMN 只有正常确认/整改/验收路径。Flowable 运行版本在根 `pom.xml` 固定为 8.0.0；工作流绑定保存精确的 `definition_id`，完成任务时也校验实例定义 ID 与绑定相同。因此仅部署新 BPMN 不会自动把在途 V1 实例迁到新路径。新增状态/关系必须等追加迁移、V2 流程和针对运行实例的迁移/阻断策略都通过集成测试后再启用。

本分支没有新增 V8 迁移、BPMN 或状态值；没有连接、改写或应用 NAS/数据库迁移。主实施线程正在处理 OpenAPI/生成器，后续如要修改 `finding-workflow.yaml`，可基于本节第 3–5 条单独合入 `relatedFindingId` 与新状态契约；本分支未改该文件。

## 后续契约验收

- PERSON 与 SAFETY 指派相同，或任一指派等于上报人：拒绝且无 finding/task/transition 写入。
- legacy `DISCOVERED` 初始化遇到不合格确认路由：保持 `DISCOVERED`、版本不变、无流程实例。
- 上报人本人调用 PERSON 或 SAFETY，即使测试将当前 BPMN task 直接改派给其成员：403 拒绝且无副作用。
- SAFETY actor 与存档的 PERSON confirmer 相同：403 拒绝且不完成 SAFETY task。
- `NOT_ESTABLISHED` 后任何同记录确认/派单/整改/验收命令都拒绝；新报告只新增并关联，不改旧 finding。
- `RETURN_FOR_INFORMATION` 只等上报人补充，补充后新复核周期回到 PERSON；不进入终态。
