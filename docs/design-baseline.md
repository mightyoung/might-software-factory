# 全面设计基线 v1.0

日期2026-10-29；状态：现行设计规范；P0已获实施授权，实际结果待证据核实。各主题只保留下列规范源；旧版本在Git与变更记录追溯，不并列两套现行规则。

## 阅读索引
1. [公共服务总体](public-services-design.md)：模块、身份权限、流程、通用台账与恢复。
2. [逻辑schema](logical-schema.md)：公共/业务/接入/事件实体字段类型、可空语义、约束、索引、迁移。
3. [接口与触发](interfaces-and-triggers.md)：请求/错误、审批命令、六类源、条件动作、幂等循环和重放、事件中心。
4. [双重预防样板](business-samples/dual-prevention-mechanism.md)：第一样板全部业务范围及明确双确认多轮链。
5. [业务组合/视频](business-configuration.md)：空间设备、算法数据接入、独立执行面、50/16目标。
6. [数据质量](data-quality.md)：原始/标准化/质量/用途、血缘、异常不可洗掉。
7. [设备地图大屏](device-map-dashboard.md)：官方接入边界、地图点位/层级、大屏数据与模板配置。
8. [测试环境](test-environment.md)：Mac只Java、LAN PG/Redis、盘点事实与未知。
9. [阶段和验收矩阵](implementation-milestones.md)、[验收明细](acceptance-plan.md)、[决策记录](architecture-decisions.md)、[变更记录](design-changelog.md)。

## 当前一致决策
- 仓库/目录规范：[README](../README.md#单仓库与目录边界)。
- Java/Spring模块化管理控制面，PG完整功能档；Flowable8+Boot4候选组合待集成。
- 环境规范：[测试环境](test-environment.md)。
- schema/adapter/动作开发注册测试发布；运行时在已注册集合组合配置，配置表单+流程预览，首期无拖拽画布/任意在线改表执行。
- 第一版电脑浏览器；只选一个业务闭环作初始实现切片，不删除整体风险隐患/设备/算法范围，不意味着只用模拟而不接真实算法。
- 隐患链保留“一名人员确认→安全部门确认→安全部门派单→整改→安全部门验收”，失败回整改多轮。
- 平台负责质量契约/校验/血缘，独立模块负责抽帧处理；异常、过期、缺失、算法失败不是无风险。
- 统一事件中心分类处理，不把设备离线、算法失败、报警、隐患视为同一可一键关闭状态。
- 50路分析/16路显示、偏实时和六类算法为待测目标；全局ReID非局部跟踪ID，专业判定/生产控制不自动化。

## 文档成熟度与未知
本轮已细化逻辑schema、字段约束、索引意图、接口错误和状态、迁移/事件/验收阶段。P0后端工程骨架、本地检查与首个远端CI运行已通过，真实PG联调仍待专用测试库；尚未生成生产可执行DDL、OpenAPI构建产物、真实设备适配代码，也没有吞吐/算法效果报告。后续PR提交的CI状态以当前head检查为准。完成结论仍需代码、测试和实际环境证据，不能写成成果。

真实接入需设备型号/固件/协议/凭据/网络权限及LAN数据库配置；行业地区/标准全文/审核责任人与实际监管规则仍待核；第三方成本与额外加速资源未授权。容量目标不自动证明Mac或免费云可承载。未知项不是常规字段偏好问题，无需逐字段询问用户。

## 核验记录
官方来源见各专题；只读硬件盘点范围及被拒操作见环境文档。仓库文档以提交SHA可追溯，读回成功仅证明内容已保存，不证明服务运行或性能达标。后续新增选择以版本化变更记录更新，禁止静默把未验证改成已通过。

## 新增正式容量要求
至少200名同时并发用户（非200QPS/仅在线）；[容量与页面查询预算](capacity-and-query-budget.md)定义混合负载、连接池/有界队列、全局租户会话预算、背压取消及权限版缓存。50分析/16显示为独立维度，不默认200人同时各看16路；所有容量未测。

## 跨文档语义协调
聚合version/revision表示乐观锁；不可变流程/算法/schema/地图版号另列。事件ID、trace关联、订阅seq和分页cursor不混用。地图outbox复用公共持久事件，不另造第二总线。报警现行按condition与ack/suppression独立维度，确认不恢复；多态业务引用须注册解析/校验，不虚称所有UUID有SQL外键。空间WORKSHOP映射现有工房，设备/数据源只维护一份事实。

## 前端3D参考审阅
[附件E3代码静态审阅与简化厂区建议](frontend-3d-reference-review.md)：可借鉴坐标/参数化模型/生命周期；不是成熟WebGL引擎，无视频接入验证，许可未明不得直接复制实现。AI卫星/实景→受控SceneJSON属于候选增强；2D保底，3D非默认首期，禁止任意AI JS执行和伪测绘。未上传附件源码/数据。

## Three.js最终选型补充 — 2026-10-29

Three.js已选定；SceneJSON/GLB、ID映射、React隔离、2D保底及R3F边界的唯一规范见[设备地图大屏：Three.js最终选型](device-map-dashboard.md#threejs最终选型补充--2026-10-29)。
## 业务需求入口
[业务需求与使用场景v1.0](business-requirements-and-scenarios.md)提供BR-001至BR-012、角色目标与端到端步骤、例外和设计/验收映射。作为业务阅读入口，不替代具体schema与工程门禁；真实实现进度以代码分支/CI证据为准。

## 验证状态表
|事项|当前状态|证据/下一门禁|
|---|---|---|
|业务范围/多租户/外接算法/Three.js|设计已定|BR-001至BR-012与专题规范；不等于实现|
|P0实施|首批工程骨架、本地构建、模块契约测试和首个PR CI通过；LAN集成仍阻于测试库材料|提交、当前head CI与LAN集成回执；详见[P0工程基线](p0-engineering-baseline.md)|
|Boot/Flowable/数据库事务组合|Flowable BOM错配已由运行时测试发现；ADR023对齐后本地代码/模块检查通过，真实引擎DB启动未验|PG18 context/schema、timer/callback回滚、投影一致性|
|200并发、50分析、16显示|正式目标，待测|容量矩阵与真实硬件/数据报告；不是QPS承诺|
|LAN接入/媒体执行环境|连接参数待安全配置；媒体未定|专用测试权限及实际设备/算法来源|
|版本许可/SBOM|逐包待构建核验|项目LICENSE待后续选择，不阻已授权P0|
|行业合规/算法效果|待真实材料和专业验证|不以合成案例或AI预标注作人工真值|

## 术语与唯一规范索引
- 租户/成员/授权：公共服务设计；实体约束以logical-schema为准。
- 报警condition、ack、suppression：logical-schema“报警维度规范”；报警不等于隐患。
- 流程运行权威/业务投影/transition：interfaces-and-triggers第5节。
- 页面查询入口：interfaces-and-triggers第7节；预算执行：capacity-and-query-budget。
- domain_event.eventId（事实）、traceId（调用关联）、订阅seq（授权流顺序）、nextCursor（分页）：设备地图大屏第15节及接口契约，禁止混用。
- Three.js/SceneJSON/GLB/R3F边界：device-map-dashboard“Three.js最终选型”；目录：README；环境：test-environment。
