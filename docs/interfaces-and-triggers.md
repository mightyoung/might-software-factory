# 接口与触发运行契约 v1.0

仅设计，未部署端点。HTTP基路径 /api/v1；tenant从显式路由上下文经身份校验得到，不信任请求体。OpenAPI/异步契约在实现时以此定义机器文件，当前没有生成假接口SDK。

## 1 请求响应
读取返回data、meta(requestId,traceId,schemaVersion)；分页data.items、page(size,nextCursor?,total?)，total未知不填0。写请求携带Idempotency-Key、expectedVersion及actor会话；创建返回201+稳定ID/version，异步202+runId/statusUrl，不能返回成功业务终态。服务身份携带经过验证的scope和租户绑定。

错误体固定code、message、traceId、retryable、fieldErrors[{field,reason}]，可附currentVersion仅限有权对象；不得回显秘密、SQL及跨租户对象存在性。按动作声明是否安全重试。批量返回jobId，结果逐项{targetId,status,errorCode,resultRef}，不能静默吞失败。

|错误码|HTTP|处理|
|---|---|---|
|AUTH_REQUIRED|401|重新认证|
|ACTION_DENIED|403|不可自动重试|
|RESOURCE_NOT_VISIBLE|404|统一隐藏无权/不存在详情|
|TENANT_INACTIVE|403|停止当前租户操作|
|VERSION_CONFLICT|409|读最新并人工/业务合并，禁盲覆盖|
|IDEMPOTENCY_CONFLICT|409|同键异参拒绝|
|REFERENCE_OUT_OF_SCOPE|422|校验引用，不泄漏目标详情|
|QUALITY_NOT_ACCEPTABLE|422|显示标签/门槛，不补假值|
|CONFIG_VERSION_UNAVAILABLE|409|阻止运行，明确缺失版本|
|WORKFLOW_ASSIGNMENT_UNRESOLVED|422|进入可授权修复的分派异常|
|TRIGGER_LOOP_GUARD|409|停止事件链并审计|
|RESULT_STALE|409|归档迟到结果，不再实时联动|
|RATE_LIMITED|429|按Retry-After有界退避|
|DEPENDENCY_UNAVAILABLE|503|结果不确定时先查状态|

## 2 公开服务接口
|接口|必要输入|返回/约束|
|---|---|---|
|GET /session/bootstrap|tenant/app上下文|当前身份、轻量应用列表、当前应用菜单、权限摘要及版本；不含全字典/表单|
|GET /entities/{key}/schema|version? |已授权字段/动作描述；ETag|
|POST /entities/{key}/query|filterAST,sort,page,size,fields|只支持注册类型/字段/操作，权限谓词分页前执行|
|GET /references/{type}|search,cursor,size|仅有权候选ID+label，不全量组织|
|POST /findings|发现描述、时间、关联?、证据|创建待第一人员确认，不自动进入整改|
|POST /findings/{id}/commands|command,expectedVersion,round?,payload|命令白名单和状态/职责校验|
|POST /workflow/instances|bindingKey,businessId,businessVersion,submissionNo|锁具体定义ID，防重复发起；无配置报错|
|POST /workflow/tasks/{id}/commands|APPROVE/REJECT/TRANSFER等、expectedVersion、reason|读回tenant/参与者/当前轮；引擎与业务同事务|
|POST /algorithm-runs|bindingVersion,inputRefs,logicalKey|202 runId，检查质量/预算/权限|
|GET /algorithm-runs/{id}|无|状态、进度（只有有依据才填）、结果/失败码|
|POST /algorithm-runs/{id}/cancel|expectedVersion,reason|CANCEL_REQUESTED；实际确认后才CANCELLED|
|POST /algorithm-results|runId,resultId,版本及结果|验签/重放/tenant/版本；重复同hash返回原收据，异hash冲突|
|POST /configuration-releases|bundleVersion,scope,expectedVersion|校验→影子→审批发布，不任意脚本|
|GET /events|type,object,state,time,cursor|设备健康/算法失败/检测报警/待确认隐患分类|
|POST /events/{id}/actions|已注册动作+版本|同类事件各有动作，不用统一“关闭所有”|
|POST /replays|range,ruleVersion,mode|默认SHADOW；实际副作用须单独授权|

逻辑接口不要求前端通用CRUD万能路由绕过聚合服务。entities查询可统一，修改由注册命令处理器执行业务约束，禁止客户端选择任意表。

## 3 统一触发源→条件→动作
触发源枚举：MANUAL、DEVICE_SIGNAL、ALGORITHM_RESULT、SCHEDULE、EXTERNAL_API、BUSINESS_STATE_CHANGED。每种源适配成domain_event，不让规则直接读取设备密码或任意库。**台账新增是动作的结果**；新增/状态改变可发布业务事件再被规则订阅，但来源系统、执行者、关联根事件、规则版本属于血缘字段，不混成单个来源类型。

条件AST仅允许注册字段、类型运算符、时间窗、质量要求及受限组合。动作只从允许列表选CREATE_PENDING_FINDING、CREATE_TASK、NOTIFY、UPDATE_ALLOWED_STATE等，具体scope与参数schema单独授权。自动专业认定、关单、生产控制不在默认允许列表。人工源不自动赋予操作者未获权限。

事件信封：eventId、tenantId、type、schemaVersion、subject{type,id}、occurredAt/receivedAt、payload、quality、originSystem、sourceKind、actor/delegator、correlationId、causationId、rootEventId、depth、ruleVersion?、replayRunId?。eventId表示唯一事件，correlation串联请求，root/causation表示因果链，不能互换。

执行唯一键：(tenant,eventId,ruleVersion,actionKey,ordinal)防同事件重复；另加(rootEventId,ruleVersion,actionKey,target)重入保护，防循环不断造新eventId。提交事务内写action_execution及目标业务变化/事件登记，外部副作用提交后投递。迟到/重复事件保留收据，不能倒退终态或重复建隐患。

## 4 循环与重放
发布静态检测规则图显式环、同对象无条件写回、相互派单；不通过默认拒绝发布。受控允许的周期流程必须有显式终止条件、人工审核和运行配额。

运行默认设计限值：因果深度8、单根事件动作100，租户速率/队列上限按部署配置；这些为待验安全默认而非性能成果。无实际语义变化不发STATE_CHANGED；不能靠不断写updated_at制造事件。触达上限进入隔离异常，保留链路/原因、通知维护者，不静默丢失或无限重试。

重放默认SHADOW，只比较规则输出，不发送真实通知/建单/付费算法；REPAIR模式需指定租户/对象/时间范围、审批人、幂等空间和副作用清单。模拟事件/历史回放有显式标志，不能冒充新现场观测。恢复备份后先禁副作用，核配置/历史版本和投影，再受控恢复任务。

## 5 当前隐患命令状态
DISCOVERED→PERSON_CONFIRM_PENDING→SAFETY_CONFIRM_PENDING→SAFETY_DISPATCH_PENDING→RECTIFYING→ACCEPTANCE_PENDING→CLOSED；安全验收失败开启新round并回RECTIFYING。发现创建可直接表现为PERSON_CONFIRM_PENDING，但仍留发现事件与第一确认任务。

命令分别confirmByPerson、confirmBySafety、dispatchBySafety、submitRectification、acceptBySafety、rejectAcceptance、requestExtension、approveExtension、reopen；每个有职责、状态、版本、证据门槛。PERSON确认只能一名已解析成员；安全部门动作按角色及实际任务人。退回补充和有理由作废是显式分支，不删除发现。原轮迟到回调拒绝，不得关闭新轮。

Flowable为运行执行权威，业务投影与之同命令更新；失配进入修复告警，禁止手动直接改状态假修复。通知书生成/送达、整改提交/验收通过分别记录。紧急处置和依法报告并行，不被流程锁住。

## 6 配置表单与事件中心
首期使用**配置表单+只读流程预览**，不做拖拽画布。表单从注册schema生成，可选已发布流程/处理链/算法/条件动作，发布前显示影响范围、版本差异、循环检查和影子结果。预览不是执行器，也不提供任意节点代码。

事件中心集中呈现设备离线、数据过期、算法失败、检测告警、待确认隐患。共享时间/对象/严重度/来源/追踪查询，但各类型有独立处理：设备恢复、算法重试、报警确认/恢复、隐患审批整改不能互相替代。跨类型关联展示，不一键批量“恢复/关闭”不同状态机。

第一实施切片只选一个端到端闭环验证框架，但保留整体风险与隐患范围，也不推断只接模拟算法或不接真实算法；真实接入必须具备契约、权限和环境，不能未经连接就声称已联通。

## 7 页面查询契约扩展
POST /page-queries接收pageKey/queryVersion/filters/widgetRequests，统一鉴权/预算后拆受控查询，按widget返回状态、asOf、data/error。不允许任意SQL或把全页拼一条巨SQL。完整/渐进返回及取消由契约声明，详见[容量预算](capacity-and-query-budget.md)及[地图大屏](device-map-dashboard.md)。
