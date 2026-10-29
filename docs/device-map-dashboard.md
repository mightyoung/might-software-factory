# 设备接入、多级地图与通用大屏设计 v0.1

2026-10-29，云端公开资料研究与设计；未连接任何设备、未扫描网络、未安装SDK、未编码或部署、未测试50路分析/16路展示。与现有 business-configuration.md 的 Equipment、DataSource、RegionDefinition、事件分层及媒体执行面保持一致。以下结构/API是项目设计建议，不是厂商原生API。首期PC大屏、图片平面图；不引入付费GIS，不把地图/设备位置外传。

## 1 设计结论

设备独立登记，地图只引用设备，不在每张地图复制设备或报警状态。园区→区域→车间等空间对象可配置，地图可下钻返回；设备可在总图和局部图各有点位。物理空间、地图图像坐标、摄像头画面ROI三者分开，不能直接当同一坐标系。

接入采用能力驱动适配器：厂商平台API、设备协议/SDK、ONVIF、RTSP、GB/T 28181分别登记。RTSP地址可收流不代表设备发现、PTZ、报警或录像查询全部可用。优先满足已选型号实际需求，非三厂所有SDK和所有协议一起实现。传感器同理，按实际网关选择MQTT、OPC UA或Modbus适配；首期只读采集，不下发生产控制。

管理Java服务只负责资产/空间/配置、鉴权、授权媒体会话、状态与事件汇聚；原生厂商SDK、视频解码/转码/抽帧/AI在独立执行器。MacBook Air仅Java后台，PostgreSQL/Redis为局域网外部服务，媒体/算法执行环境未定。

## 2 三厂官方证据矩阵与覆盖缺口

核查时间2026-10-29 UTC；“官方介绍已读”不是已联通，更不是所有型号兼容。未获取需登录/授权资源，不用第三方包装库代替厂商兼容证明。

|路径|已核读证据|边界/未知与工程决定|
|---|---|---|
|海康设备ISAPI|[官方集成中心](https://tpp.hikvision.com/tpp/IntegrationCenter)说明其为基于HTTP的REST风格设备/服务器通信协议|按型号、固件和能力接口逐项确认。平台同时明确部分页面需登录，完整接口覆盖、费用及许可未知，不视为“所有API免费公开”|
|海康平台API|同页区分HikCentral Professional OpenAPI的视频/报警等HTTP能力，和Hik-Partner Pro平台能力|必须存在对应平台及版本、已授权资源/应用；不能拿平台API直接假设所有裸摄像头都支持，也不把HPNetSDK当设备HCNetSDK|
|海康设备Network SDK|[官方SDK入口](https://www.hikvision.com/en/support/download/sdk/)、[区域SDK页](https://pro-av.hikvision.com/us-en/support/download/sdk/)搜索索引显示Windows/Linux包及许可条款|本次原文分别为空/403；操作系统包信息仅索引线索，当前版本/CPU ABI/macOS ARM64/再分发许可未核清，不能据此部署Mac原生库|
|大华设备NetSDK|[官方下载页](https://www.dahuasecurity.com/download-center/softwares?menu_id=527)原文给SDK入口，链接到[伙伴知识库](https://depp.dahuasecurity.com/knowledgeBase)|当前知识库为空，旧伙伴SDK页仅JS壳；NetSDK具体包、方法、平台ABI/授权与型号固件待核，不编造macOS支持|
|大华DSS平台|[DSS Professional V8.0.2手册](https://www.dahuasecurity.com/asset/upload/uploads/soft/20230423/Dahua-DSS-Professional_Users-Manual_V8.0.2_20230423.pdf)PDF页12说明API/SDK/ONVIF集成；页23–24说明试用/付费授权及功能/通道许可|只证明该版本产品公开说明，不代表当前DSS价格/接口包/许可范围。2025集成方案PDF原文因14.8MB工具大小限制未读，摘要中的具体方法不列已核接口|
|宇视设备NetDEVSDK|[官方Windows64包页](https://global.uniview.com/Support/Download_Center/SDK/201603/788662_168459_0.htm)显示2024-03-03、V2.7.0.1，说明私有网络协议、设备/资源/监控管理与SDK/手册/MFC和C#示例，并列产品系列|证明该历史包说明与系列范围；不是最新版判断，不代表未列型号或全部固件/方法支持。公开索引另有Android/iOS/Windows32，Linux/macOS ARM64和再分发许可本次未核明|
|宇视平台接口/UNISEE|[UNISEE官方入口](https://global-open.uniview.com/en/home/onlineDebug)原文为空；[IMOS目录](https://cn.uniview.com/Service/Service_Training/Download/SDK/1/)原文为空|不把第三方转载的IMOS REST/SDK能力当已核。平台版本、API、授权、OS待取得官方可读资料|
|ONVIF|[Profile T](https://www.onvif.org/profiles/profile-t/)涵盖视频、元数据和事件等，部分能力有条件；[符合性数据库](https://www.onvif.org/conformant-products/)明确符合性绑定具体固件/软件版本|逐型号/固件/Profile核验并联调；品牌会员身份不等于所有产品符合。PTZ只对确有硬件/服务/权限者开放；不要自动启用广播发现或远程控制|
|RTSP|[RFC7826](https://www.rfc-editor.org/rfc/rfc7826.html)是实时流会话控制规范|摄像头实际RTSP版本、认证、编码、传输及NVR通道映射单独确认；RTSP2.0规范不证明目标摄像头支持2.0。发现、控制、事件是独立能力|
|GB/T 28181|作为条件式视频联网适配方向，与直接RTSP及ONVIF分列|本次未核读标准全文和具体设备兼容清单，不填确定标准年版或宣称三厂全支持。仅在客户平台/设备明确要求时定义SIP身份、目录/心跳、媒体会话和安全要求再适配|

每个接入验收条目保存 vendor、model、firmware、platformVersion、adapterVersion、SDK/API版本、OS/arch、licenseEvidence、能力测试日期和逐项结果（SUPPORTED/UNSUPPORTED/UNKNOWN/FAILED）。本轮没有实际型号/固件清单，因此不能产“已兼容三厂”的结论。官方页面可下载不等于SDK可再分发；仓库不放厂商二进制或凭据。首期摄像头以经授权配置的已知端点开始，不自动扫描局域网。

## 3 可实施数据结构

通用约束：UUID、tenant_id、created/updated_at、乐观锁revision；跨对象引用带tenant复合外键；所有名称可改但ID稳定。审计版本不覆写。

|对象|核心字段与约束|
|---|---|
|SpatialNode|id,parent_id,type(PARK/AREA/WORKSHOP等注册类型),name,responsibility_binding_ref；禁止循环和跨租户父子，层级深度受限；空间目录不复制组织树|
|Device / Equipment|复用已有独立Equipment资产，device_kind(CAMERA/SENSOR/GATEWAY)、vendor/model/firmware、责任对象关联、lifecycle；相机是视频采集设备，不另建一份资产|
|DataSource|复用source_id、equipment_id、channel_key、protocol/adapter_ref、endpoint_ref、secret_ref、capability_profile、health；多镜头/多通道独立source，不能靠设备名拼接通道|
|MapDefinition|id,spatial_node_id,name,published_version_id；地图归属空间节点，不将图片名作为空间ID|
|MapVersion|map_id,version,asset_ref,sha256,width,height,orientation,coordinate_system(IMAGE_PIXEL),status,DRAFT/PUBLISHED/RETIRED,created_by；首期PNG/JPEG经文件安全校验，限制字节/像素，拒绝任意SVG/HTML执行|
|MapPlacement|id,map_version_id,target_type,target_id,x,y,rotation,label_override,style_key；target为设备/空间/业务对象，重复视图只引用同target；x/y是图像原始像素，发布校验边界|
|MapHotspot|id,map_version_id,polygon,child_map_id,target_spatial_node_id；显式下钻链接，禁止循环/越权，区域多边形不冒充视频ROI|
|DeviceStatusProjection|device_id,source_id,connectivity,quality,last_event_at,received_at,state_version；是源状态投影，不落每个地图点副本|
|AlarmEpisode|复用事件中心episode/rule/object/source/version；condition(ACTIVE/RECOVERED/UNKNOWN)、acknowledged_at/by、suppression独立字段，防止单enum误把确认当恢复|

地图版本换图必须产生新版本。单纯等比缩放可显式变换坐标并预览确认；图片内容/边界改变不能盲按比例复制。每个迁移点保留旧版本坐标、变换说明与确认人；未确认点不在新发布图假装准确。前端按原图尺寸、contain留白、缩放平移变换显示并做点击反算，地图坐标与视频框坐标各自使用版本。

## 4 地图交互和报警状态

中央当前层地图、面包屑和返回上层、层级/名称检索；区域点击进入子图，返回保留上一层缩放/选中（权限仍重验）。点位列表与地图互为替代，避免密集点遮挡；聚合/过滤不删设备。点开详情抽屉：资产、责任部门、来源/数据新鲜度、状态、事件及有权业务对象；视频设备按能力显示直播/最新图片，传感器显示值/单位/质量/历史入口。不能播放时显示断流/未授权/不支持原因，不用静态图片冒充实时。

点位用颜色+图标+文字表达正常/报警/离线/未知；未确认高优先级报警可有限脉冲，支持减少动画与暂停，不闪整屏，不仅靠红绿。确认是“已读/已接手”类动作，不清除ACTIVE条件；恢复必须来自事件规则/可信状态。断流导致UNKNOWN，不能自动转绿色。优先级突出报警但同时保留离线/未知标记。

上级区域聚合基于其范围内授权可见的独立deviceId/episodeId去重，而非地图点数相加；同设备在多地图出现不重复计数。显示报警数、设备异常数和更新时间，区分当前活跃/历史/未确认指标。不能借汇总数字泄露无权子区域；权限缩小时清除旧聚合。

## 5 API草案与权限

- GET /api/spatial-nodes?parentId=…；POST/PATCH节点需配置权限和revision。
- POST /api/maps；POST /api/maps/{id}/versions（受控文件assetRef）；PUT /api/map-versions/{v}/placements；POST /api/map-versions/{v}/validate；POST …/publish。发布检查全部目标存在、有权、坐标与层级无环；If-Match防并发覆盖。
- GET /api/maps/{id}/view?version=published 返回地图元数据、有权点位、热点和快照标识；不返回secret/RTSP URL。
- GET /api/devices/{id}/overview 返回按权限裁剪的设备/source/状态/责任/业务链接；资产编辑、设备接入配置、看直播、看回放、看证据、确认报警分别授权。
- POST /api/media-sessions {sourceId,purpose,profile} 返回短时受限播放会话、过期时间和浏览器协议。媒体网关保存源凭据；日志脱敏；关闭/超时释放会话；网关未部署时明示不可用，不令Java/Mac临时转码。
- POST /api/alarm-episodes/{id}/acknowledgements {reason,expectedVersion} 需幂等键；不提供把ACK等同恢复的API。PTZ等写设备命令不属首期只读大屏。

所有读/写/聚合/媒体会话使用服务器认证租户上下文和行级权限，不能相信body tenantId。图片文件、缩略图和深链同样鉴权；屏幕浏览权不自动给予设备凭据/所有台账数据权。

## 6 WebSocket一致性契约

WebSocket只承载授权范围内状态/事件增量、地图换版通知及图表缓存失效提示，不承载视频字节、不广播50路每帧AI结果给地图。视频交互结果使用独立受控可见源订阅与有界频率。

1. 同源认证会话+Origin校验，订阅通过服务端解析mapId/spatialScope；不接受任意tenant topic。权限变更重新计算或撤销订阅，注销立即断开。跨域部署另定受控短期票据，禁止长期token出现在可记录URL。
2. 建立订阅取得streamEpoch/subscriptionId和一致性snapshot（状态及cursor）。服务端先捕获快照边界并缓存随后增量；客户端应用快照再接seq>cursor，避免“先HTTP后WS”漏事件竞态。
3. Envelope={schemaVersion,subscriptionId,streamEpoch,seq,eventId,eventType,entityId,entityVersion,eventTime,emittedAt,payload}。seq为此授权订阅的连续序列，不因过滤全租户事件制造假缺口；eventId去重、entityVersion拒绝旧状态覆盖。
4. 重复忽略；短暂乱序有界缓存等待，缺口/缓冲溢出发resync，不能无限等；重连携带epoch,lastSeq，仍在保留窗口则补发，否则RESYNC_REQUIRED取新快照。epoch变更/权限版本变更必须重建，不能续用旧数据。
5. 心跳超时显示连接中断及最后更新时间；退避抖动重连、有界队列和慢消费者限流；队列丢弃必须明示重新同步，不能静默漏报警。刷新页面不改变报警确认/恢复状态。
6. PostgreSQL事件/状态为持久事实；Redis可作缓存/分发但Pub/Sub丢消息不能当可重放日志。状态更新与持久outbox在同事务，投递至少一次+幂等；实现可按实际组件选择，不强行新增MQ。

## 7 通用大屏：模板与组件，不造重型BI

首期模板：中央多级地图；左右若干指标/折线/柱图/饼图插槽；底部图片轮播或滚动台账。组件可隐藏/替换，地图并非每种大屏强制项。采用受限网格插槽+属性面板、预览、发布；不建设任意拖拽脚本设计器。PC大屏以可读字号/无溢出为准，手机后续。

成熟机制依据：
- [Grafana Dashboards](https://grafana.com/docs/grafana/latest/visualizations/dashboards/)把多个panel组织成大屏并解耦数据源和可视化；本案借鉴组件化，不嵌入整套Grafana或允许任意数据源URL。
- [Superset 6.0.0官方教程](https://superset.apache.org/user-docs/6.0.0/using-superset/creating-your-first-dashboard/)说明dataset字段能力、metric/维度、图表和发布。本案只参考注册数据集/指标及草稿发布，不开放其SQL编辑能力，不照搬其可绕过dataset检查的dashboard角色模式。
- [ECharts dataset](https://echarts.apache.org/handbook/en/concepts/dataset/)支持数据与图表配置分离、复用和映射，适合作为候选渲染层；本案权限、聚合、缓存由服务器保证，不依赖前端图表隐藏数据。

WidgetDefinition={id,type,componentVersion,title,slot,visible,queryRef,displayOptions,refreshPolicy,interactionPolicy}。
DashboardVersion={id,dashboardId,version,templateKey,spatialScopeRef,timezone,widgets,status,createdBy,publishedBy,publishedAt}。
RegisteredDataset={key,schemaVersion,ledgerType,grain,primaryKey,allowedFields,temporalFields,dimensions,allowedMetrics,permissions,joinPolicy,costLimit}。
MetricDefinition={key,version,datasetKey,aggregation,fieldRef,distinctKey,unit,nullPolicy,description,permissionRef}；由开发者注册测试，运行时仅选择已批准指标，不输入SQL/JS/任意表达式/API地址。

|组件|允许配置与校验|
|---|---|
|折线/柱图|选台账dataset、明确时间字段、范围、日/月等允许粒度、计数或数值指标、可选维度；数值必须支持聚合/单位匹配，不能对文字求和|
|饼图|周期、枚举分类字段及指标；按枚举稳定ID分组后映射标签，null/已删除枚举归明确“未知”，限制类别数/其他分组规则并显示口径|
|数字卡|标题、一个已注册指标、单位/精度、可选比较周期；标题不改变指标实际定义，不由任意台账字段文字拼成执行表达式|
|滚动台账|dataset、授权过滤条件、展示字段、稳定排序（主排序+ID）、条数上限、滚动间隔；暂停/点击查看详情，敏感字段遵从字段权限|
|图片轮播|dataset、图片/附件字段、过滤范围、排序、记录与每记录图片数量上限；字段需具有image能力，文件类型实际校验，无图显示占位不拿其他租户图片补齐；有界预加载，暂停轮播|
|地图|mapId/发布版本、范围、点位类型、图层与聚合方式；不以组件配置扩大设备权限|

QuerySpec示意：{datasetKey,datasetVersion,metricKeys:["hazard.count_records"],time:{field:"discoveredAt",kind:"EVENT_TIME",from,to,grain:"MONTH",timezone:"Asia/Shanghai"},dimensions:["statusId"],filters:[{field:"workshopId",op:"IN",values:["…"]}],orderBy,limit}。字段、op、值类型、范围、行数、成本均服务端校验；参数化执行。不是给用户SQL终端。

时间口径必须区分事件发生eventTime、记录创建createdAt、业务日期businessDate；默认值来自dataset定义且UI明确显示，不能把创建时间冒充发现时间。保存时区（默认Asia/Shanghai但可在允许集合中选）、UTC边界及左闭右开区间，按业务时区分月，测试月末/跨时区。DATE字段不强造UTC瞬间。

COUNT_RECORDS以台账主记录为粒度，明细条数另指标；多表关系可能放大记录数，必须按注册grain和joinPolicy处理，COUNT_DISTINCT明确key；金额/数值不重复累计。零值、有权范围内无记录、空值、未授权、加载失败、数据过期是不同状态，失败不渲染0。补齐空时间桶仅当查询成功且口径允许；不把未知样本填0。

## 8 大屏查询、刷新、发布边界

GET /api/dashboard-components 和 /api/datasets/{key}/capabilities 返回当前用户可选能力；POST /api/dashboard-queries 批量查询已注册QuerySpec；返回queryId,metricVersion,grain,timezone,asOf,dataWatermark,status,rows,units,partialReason。客户端可显示旧数据但必须标stale，不能混作实时。

服务端先执行行/字段/租户权限再聚合，图表点击下钻到同口径有权台账，不先聚全量后靠前端过滤。缓存key含tenant、有效数据范围/授权指纹及permissionVersion、dataset/metric/config版本、时间边界、过滤/粒度；不能只按dashboardId缓存跨用户结果。权限撤销失效并清理浏览器已展示敏感数据。

统一刷新调度合并同query与同范围请求、限制并发和最小刷新间隔，页面隐藏暂停普通轮询/轮播并释放不可见媒体；回到前台按新鲜度补取。高频事件对图表发合并失效提示，不每帧每点全量重算；异常风暴限流时明确延迟与状态。视频16窗与分析50路独立，不因为地图点数增加自动播放全部设备。

草稿→schema/权限/引用/成本校验→预览（真实授权数据或明确标注模拟）→发布不可变版本→按需回退。编辑权限、发布权限、查看权限分开，公开分享不默认开启；编辑者无权字段不能借预览突破。发布时固定组件/指标/schema版本，运行时权限仍实时校验；回退不还原已撤销权限。配置变更审计、If-Match冲突保护，旧日期/版本保留。

## 9 验收清单（均待执行）

1. 同一设备两图打点只计一个设备；多级下钻/返回、删除/迁移引用、跨租户热点及循环校验；无授权子图不泄露计数。
2. 地图换图分辨率、留白缩放、平移、旋转下点位/点击正确；内容变更未经确认点位不发布；地图ROI/视频ROI不混用。
3. 报警ACTIVE→ACK仍报警；恢复独立；断流UNKNOWN不绿；颜色之外文字图标可理解，减少动画可用；上级聚合去重。
4. WebSocket快照竞态、重复/乱序/缺口、过期游标、epoch变化、权限撤销、注销、慢客户端、重连风暴均无越权/无静默状态错误。
5. RTSP凭据/源地址不进浏览器或日志；播放令牌过期、观看撤权、图片鉴权、接口SSRF和错误协议被阻断。
6. 每厂实际型号/固件/SDK许可与能力逐项验证；无法下载的官方接口只标未知。未接入媒体层时Java和地图可工作但不伪报直播成功。
7. 图表粒度/日期边界/枚举改名/null、主记录vs明细COUNT、DISTINCT及多表重复、无数据vs零vs故障验证；不同权限用户缓存隔离和指标下钻一致。
8. 图片轮播无图/失效附件/无权限、滚动台账稳定排序/条数上限、隐页暂停、异常风暴合并刷新，版本发布回退和冲突检查。
9. 50路分析+16可见视频是系统目标，不是Mac性能结论。代表性码率/分辨率/编码/任务/硬件下测媒体/AI/WS/图表/Java各自p95延迟、CPU内存、带宽、丢帧积压及登录台账不被拖慢；结果、阈值和不达标项如实报告。未知媒体硬件/设备清单不阻塞本设计，但阻塞真实兼容/容量验收。

## 10 实现级存储约束（与全局logical-schema对齐）

本节为DDL等效设计，不是已执行migration。复用全局equipment、data_source、region_version（视频ROI）、domain_event、文件/权限/审计表；上文驼峰对象仅为领域名称，以下为新增表建议名。所有新增表主键id uuid，tenant_id uuid NOT NULL，UNIQUE(tenant_id,id)；外键必须同时包含tenant_id，created_at timestamptz NOT NULL。除下表标?可空外均NOT NULL，FK删除默认RESTRICT；业务删除走归档/新版本。

|表|字段（?可空）|必要索引、约束|
|---|---|---|
|spatial_node|parent_id uuid?; kind varchar; name varchar; revision bigint; archived_at timestamptz?|FK tenant,parent_id→本表；INDEX tenant,parent_id,archived_at；CHECK parent_id<>id；祖先环/深度在锁定同租户树变更事务内校验，不能只靠前端|
|map_definition|spatial_node_id uuid; name varchar; published_version_id uuid?; revision bigint|FK空间节点；INDEX tenant,spatial_node_id；publishedVersion必须属于同map（复合关联），不允许串图|
|map_version|map_id uuid; version int; file_id uuid; file_sha256 varchar; image_width int; image_height int; orientation smallint; status varchar; published_at timestamptz?; published_by uuid?|UNIQUE tenant,map_id,version；UNIQUE tenant,map_id,id；CHECK宽高>0；发布后内容不可变；published_*只在发布态有值；文件FK采用公共文件模型|
|map_placement|map_version_id uuid; placement_key uuid; target_kind varchar; equipment_id uuid?; spatial_node_id uuid?; business_object_ref uuid?; source_id uuid?; x numeric; y numeric; rotation numeric; label varchar?|CHECK三种target恰一非空且与kind一致；对应tenant复合FK；UNIQUE tenant,map_version_id,placement_key；INDEX tenant,equipment_id,map_version_id；source_id如有必须属于该equipment；坐标在图边界内事务校验|
|map_hotspot|map_version_id uuid; polygon jsonb; child_map_id uuid; target_spatial_node_id uuid; label varchar?|INDEX tenant,map_version_id；childMap必须属于目标空间；多边形最少3点、有界长度/有限数值且图内；下钻图关系防环、不可跨租户；不是region_version|
|screen_definition|name varchar; published_version_id uuid?; revision bigint; archived_at timestamptz?|publishedVersion复合FK到本screen；查看/编辑/发布授权用公共权限，不存越权userId数组|
|screen_version|screen_id uuid; version int; template_key varchar; template_version int; timezone varchar; default_spatial_node_id uuid?; status varchar; published_at timestamptz?; manifest_hash varchar|UNIQUE tenant,screen_id,version；UNIQUE tenant,screen_id,id；JSON清单与规范化widget rows在一次事务写；不允许两个真相分开修改|
|screen_widget|screen_version_id uuid; widget_key uuid; component_key varchar; component_version int; slot_key varchar; position jsonb; title varchar; query_spec jsonb?; map_id uuid?; display_options jsonb; refresh_seconds int|UNIQUE tenant,screen_version_id,widget_key；同slot按模板容量校验；CHECK refresh_seconds>=配置下限；组件schema限定额外属性；map/widget类型的query/map字段按类型oneOf|
|dataset_registration|key varchar; version int; ledger_type varchar; grain varchar; schema_hash varchar; capabilities jsonb; permission_ref varchar; status varchar|UNIQUE tenant,key,version；若平台级模板，先显式实例化/授权到租户，不靠NULL tenant共享；运行期只能选已发布登记|
|metric_registration|key varchar; version int; dataset_key varchar; dataset_version int; aggregation varchar; field_key varchar?; distinct_key varchar?; unit varchar?; null_policy varchar; approved_definition jsonb|UNIQUE tenant,key,version；FK dataset复合key/version；COUNT_RECORDS可无field，SUM必须numeric，DISTINCT必须有已允许key；复杂自定义指标通过代码评审而非任意SQL存配置|
|screen_publish_audit|screen_id uuid; version_id uuid; previous_version_id uuid?; reason varchar; actor_id uuid; permission_version bigint; manifest_hash varchar|INDEX tenant,screen_id,created_at；append only；回滚发布旧清单生成新发布记录，不能覆盖历史|

source状态投影复用既有状态表；如新增source_state_projection则UNIQUE(tenant_id,source_id)，state_version bigint、event_time/received_at、connectivity/quality、last_event_id FK domain_event，更新时间必须幂等单调。报警episode必须独立于map_placement；ack与condition不同维度。事件ID/根事件/因果ID/关联ID沿用全局定义，禁止混成一个“trace id”。列表nextCursor只用于分页，绝不是WebSocket事件resume cursor。

地图发布伪代码：锁map_definition revision → 校验文件已安全处理且可读 → 检查所有设备/热点引用及权限 → 锁租户地图关系版本并检查无环 → 检查坐标与迁移确认 → 写不可变map_version+点位 → 更新published_version_id → 同事务outbox MAP_VERSION_PUBLISHED → 提交。旧版本继续用于历史证据；撤权立即生效，不因旧版本可读而还原设备权限。

## 11 配置JSON、受控查询编译与图片分页

示意结构（字符串ID为占位，不是实际资源）：

```json
{
  "screenId":"uuid", "version":1,
  "template":{"key":"map-center","version":1},
  "timezone":"Asia/Shanghai",
  "widgets":[
    {"id":"uuid","component":{"key":"map","version":1},"slot":"center","binding":{"mapId":"uuid","mode":"published"},"options":{"reducedMotion":true}},
    {"id":"uuid","component":{"key":"line","version":1},"slot":"left-top","query":{"dataset":{"key":"hazard","version":1},"metrics":[{"key":"count_records","version":1}],"time":{"field":"discoveredAt","kind":"EVENT_TIME","grain":"MONTH","range":{"type":"relative","count":12,"unit":"MONTH"}},"dimensions":[],"filters":[],"limit":12},"options":{"showFreshness":true},"refreshSeconds":30},
    {"id":"uuid","component":{"key":"image-carousel","version":1},"slot":"bottom","query":{"dataset":{"key":"hazard","version":1},"imageField":"evidenceImages","filters":[],"order":[{"field":"discoveredAt","direction":"DESC"},{"field":"id","direction":"DESC"}],"limit":20},"options":{"maxImagesPerRecord":3,"pauseOnHover":true}}
  ]
}
```

示例30秒是拟定默认配置，不是性能承诺。最终上下限在容量试验中验证。JSON Schema按component.key/version选择oneOf分支，additionalProperties=false；字段ID使用注册key，title不作为查询字段。map绑定可跟随最新发布图，但每次响应显式包含实际mapVersionId；历史证据/审计固定版本。

查询编译伪代码：

```text
compile(authenticatedSession, QuerySpec):
  tenant, principal, policyVersion := trusted context
  require active tenant membership + screen.view + dataset.read
  load published dataset/metric versions; validate component-compatible schemas
  resolve typed fields/operators; reject unknown fields, functions, SQL, URLs, scripts
  normalize time range in selected timezone; enforce bounded range/limit/bucket count
  rowPolicy := authorization scope intersect requested spatial/business filters
  fieldPolicy := allowed fields and metrics; disallow sensitive metric input leakage
  establish dataset grain; validate permitted joins; aggregate without fan-out duplication
  plan := registered query builder + typed parameters + rowPolicy before aggregation
  budget plan (joins/cardinality/timeouts); refuse expensive plan with explicit reason
  key := hash(tenant, principal scope fingerprint, policyVersion, versions, normalized plan)
  reuse only same authorized key; execute bounded query; record watermark and provenance
  return typed rows + SUCCESS/EMPTY/PARTIAL/STALE/ERROR, never convert ERROR to zero
```

浏览器不可提交原始SQL、表名、任意APIURL、回调函数或ECharts formatter脚本；只发受限QuerySpec。SQL生成采用服务器受控构造器/参数，不把客户端字段拼进SQL。注册计算指标也要校验依赖字段权限；涉及跨记录推断的敏感指标可另设指标权限。总数统计与明细分页服从同一授权范围；高基数分类拒绝或按已注册TopN规则并列“其他”，不暗中截断假装完整。

GET /api/dashboard-widgets/{id}/items?nextCursor=…只对当前发布widget允许的查询提供滚动/轮播页面。分页游标签名绑定tenant、授权版本、widgetVersion/queryHash、稳定排序最后值、asOf及expiry；排序包含唯一ID。权限/配置变化返回需重置而不是沿旧游标跨范围。新内容插入通过新快照开始刷新，不让自动滚动页不断重复/跳跃。客户端最多保留设定窗口，滚过页释放资源。

轮播返回recordId、fileId、imageDimensions、caption（转义）、鉴权thumbnail session/reference；文件服务重新确认记录+附件权限后给短期签名读取URL或同源认证读取。签名不含源摄像头密码，不能随意给任意fileId签名；禁第三方URL直接拉图，受控附件处理链才允许展示。签名过期可一次合并刷新会话，撤权则停止展示并清除缓存；重要敏感图优先经可撤销代理避免不可即时吊销的长URL。前端只预加载当前/下一个少量图，原图按需，防止台账大量图片拖垮内存。

## 12 前端生命周期、增量聚合与负载失败边界

生命周期：mount→取得已发布screen manifest与权限版本→取有权地图快照/首批组件数据→建立订阅→渲染。resize由统一ResizeObserver合并到requestAnimationFrame/节流队列，仅触发布局及坐标变换，不重新查询全部数据；组件卸载销毁图表实例、观察器、定时器和媒体session。隐藏页暂停普通刷新/轮播，取消可中断请求；恢复页检查授权、版本和watermark，过期则批量重取。只保留一个screen刷新协调器，组件不得各自无界setInterval。

前端cache按queryHash+权限版本+配置版本；切租户立即清空订阅、缓存、图像/媒体和未完成请求。每次请求带generation token，晚到旧请求不得覆盖新租户/新地图画面。WS invalidation合并debounce且maxWait有界；热点风暴只刷新受影响query，不触发全屏50路重载。缩放只变视口，图状态源仍在实体store；map points引用device/source状态，不复制各自状态机。

服务端聚合：实体状态更新→同事务写state_version/outbox→确定受影响空间祖先→按可见scope计算或标dirty→订阅消费者收版本化增量。父区域计数以独立episode/device集合为事实；不能简单“每收一条消息+1”，因为重发/确认/恢复会错算。跨多层/多图同设备以唯一ID去重；版本缺口时重新从事实快照计算。高频重算可物化分租户范围投影，但响应仍按用户权限裁剪/重聚合，不能把全区域隐藏设备数量发给无权用户。

订阅时序：

```text
Client -> subscribe(mapId, permissionVersion)
Server -> authorize; allocate streamEpoch and subscription; hold deltas after cursor C
Server -> snapshot(C, authorized states, mapVersionId)
Client -> apply snapshot C; accept buffered seq > C in order
Server -> delta(seq=C+1, entityVersion=…)
Client -> persist lastAppliedSeq only after apply
Disconnect -> reconnect(epoch,lastAppliedSeq)
Server -> authorized replay OR RESYNC_REQUIRED; never guess missing state
```

失败边界：地图文件损坏不影响设备/事件事实；地图发布失败旧版本仍服务；图表超时单组件错误不阻塞地图；媒体离线不拖死Java线程池；消息缓存丢失从持久事实重同步；算法积压不清报警；用户撤权后已在浏览器出现的内容尽力清理且停止续订，不声称能撤回已被用户拍照的数据。开发中不存在默认外部样例摄像头/真实个人视频，演示用合成数据并显式标注。

负载验收分层记录：①合成地图点位/热点规模和授权范围；②50分析源状态/事件频率以及风暴突发；③16可见视频实际codec/码率与浏览器GPU能力；④图表数量/数据量/时间范围/聚合查询成本；⑤图片大小和分页缓存上限。记录P50/P95/P99、事件到像素延迟、图表首屏/更新时间、WS队列/重同步次数、浏览器heap/FPS、Java线程/连接池/GC/CPU/内存和网络。登录/台账同时压测，定义合格阈值后才验收；不得以静态页面截图证明实时性能。原生SDK/媒体执行器不在Mac上，本次只设计，无任何上述实测成绩。

## 13 最新收口：页面查询编排与200活动用户

用户正式目标增加至少200并发活动用户，不等于200 QPS；详见 [容量与页面查询预算](capacity-and-query-budget.md)，本节补大屏边界。上文 /api/dashboard-queries 是组件查询领域入口草案，实现统一接入 **POST /api/v1/page-queries**，请求 pageKey/queryVersion/filters/widgetRequests；不并存第二套绕过编排/权限的任意查询入口。

页面查询协调中间层收集当前可见widget需求→校验组件版本和受控query→归一时间窗口/过滤/字段/指标→按租户、授权范围指纹及policyVersion、dataset/schema/metric版本生成queryKey→同key singleflight与短TTL缓存→按成本/优先级分组调度→分组件返回成功/部分/失败。不是将全页所有表拼一条巨SQL，不因最慢图表阻塞地图和数字卡。仅真正相同授权语义可合并；不同scope或不同聚合粒度不得强行复用结果。200用户共享权限也仍需会话有效性检查。

查询预算包括每页并发、每租户/用户并发、数据库有界连接与执行时限、最大时间桶/行数/字段、响应字节及结果缓存上限；超时取消向数据库/下游传播。singleflight中的单个消费者取消不得取消仍被其他消费者等待的底层查询，最后消费者取消才可取消共享工作。刷新加抖动避免200页面整点同时打库，批量请求分组响应含widgetId/queryId/asOf/watermark/status/errorCode，不以异常兜底为0。

需要同统计时点对比的组件显式标consistencyGroup，共用同asOf/一致快照或同批次预聚合版本；独立实时组件允许不同水位并各显更新时间。不能声称一次HTTP批请求自动等于数据库快照一致。缓存失效按变化实体/指标依赖/权限版本传播；过期回退须明确stale。实现具体阈值沿全局预算文档，待压测校准。

大屏增加200 WebSocket长连接的鉴权/心跳/慢消费者/增量扇出测试，200页面图表同刻刷新、权限混合缓存、区域热点事件、网络断开后带抖动重连/快照重取风暴。200用户并非默认全部同时16窗直播；媒体负载矩阵显式注明观看用户数×每人可见流数×共享源数×profile/码率、每源复用数和网关出口带宽，最大200×16如需承诺必须另行实测配置，当前不声称可达。管理面200用户、后台50路分析、单页16可见视频是三个独立约束，Mac能力未知。

## 14 最新收口：地图内嵌视频与点开弹窗同时支持

用户明确两种都要：地图直接内嵌/叠加矩形实时视频窗口；摄像头点位点击弹出小窗。首期是矩形窗口锚定地图点/区域，并非把视频做地理配准、透视投影、3D融合或自动相机标定。两种显示方式共用授权媒体会话池，仍受**当前页面总活跃可见视频16路**预算，不因增加显示模式提高上限。

新增map_video_overlay（同样tenant复合FK/id/version约束）：map_version_id、overlay_key uuid、source_id FK data_source、anchor_placement_key?、anchor_x/anchor_y原图像素、width_px/height_px、size_mode(SCREEN_PX/MAP_SCALED)、z_index int、default_visible boolean、display_mode(INLINE/POPUP/BOTH)、profile_key、options jsonb。UNIQUE tenant,map_version_id,overlay_key；源和点位必须同租户且source属于该摄像头；宽高/坐标/z层级设上下限；不允许遮挡返回/报警工具栏。存储在发布地图版本中，运行时拖动仅个人视图状态，只有map.edit权限可保存配置草稿并发布，普通看视频者不能修改公共地图。

默认锚点随地图缩放/平移，视频窗口尺寸用屏幕像素保持可读；可选MAP_SCALED时按显式规则变换，仍设最小/最大尺寸和碰撞/遮挡处理。下钻卸载上层窗口，返回按配置恢复但重新鉴权；不在不可见祖先图继续解码。弹窗可关闭/最小化，地图外视口窗口暂停或关闭；画面停在旧帧时标已暂停/数据时间，不伪装实时。布局变化只调整矩形，不改变视频源ROI或检测坐标。

媒体会话池key=(tenant,principal授权上下文,sourceId,profileKey,purpose)，同摄像头同profile的inline/popup订阅引用计数，优先共享同一接收媒体会话/轨道；具体浏览器渲染复用能力实施验证，禁止无意重复向网关收流。不同profile不能假装同会话；移除一个视图只减引用，最后消费者消失释放轨道、解码器和网关会话。超过16活跃源时提示替换/暂停一个窗口，不自动拉起第17源；同源复用不代表免费无限渲染，额外DOM解码/合成负荷仍需测量。媒体授权短期会话不含RTSP凭据；会话过期/撤权清理两种视图。

验收追加：inline+popup同源不重复上游会话；关闭一个另一仍播、全关释放；16路上限与第17路行为；图层遮挡、视口离开、下钻返回、缩放/resize、权限撤销/切租户/重连后旧画面不残留；反复开关窗口无media/DOM/WS泄漏；200用户观看组合与独立媒体层容量实测。媒体服务不在Mac。本节为新增设计，并未实现或演示。

## 15 与全局基线的命名协调

本文非带版本的/api路径为早期领域草案，当前统一/api/v1前缀；页面批查询唯一正式入口POST /api/v1/page-queries。地图HTTP版本revision映射为全局expectedVersion/If-Match乐观锁令牌；map_version.version、screen_version.version是不可变内容版号，不能当聚合并发令牌。

文中outbox指公共Spring Modulith持久事件注册/同事务待投递机制，不另建重复总线。WS订阅seq是授权流内递增序号，与domain_event.eventId、SQL分页nextCursor及traceId不同；订阅断档从持久事实快照重建。alarm_episode现行采用condition与ack/suppression独立维度，旧概念表的ACKNOWLEDGED仅表示历史展示概念，不得据此清除ACTIVE条件。

file_id复用file_object；空间中的WORKSHOP关联既有workshop实体，不再建立独立同名工房真相。spatial_node作为位置层级，workshop/production_line实体保留业务职责；映射表以tenant+spatial_node_id+business_id约束唯一。business_object_ref属于受控多态资源，不能凭UUID声明有跨所有业务表的SQL FK，须用注册资源解析器/发布校验和一致性巡检；设备/空间/source等明确类型引用使用真实tenant复合FK。

## 16 静态bootstrap与动态时效分组
大屏首次bootstrap一次取得当前授权范围内layout、map发布版本、设备点位和widget定义等静态/低变元数据；不包括所有应用配置或所有设备细节。响应带configVersion/mapVersion/permissionVersion，版本失效或权限变化后只刷新受影响部分。设备权限收缩必须清理旧点位，不能因为静态缓存而继续展示。

动态alarm/readings/stats按实时性分组由同一页面查询协调器调度，状态快照+增量推送与有界轮询可组合，去重、安全key缓存、有界并发、独立partial failure及新鲜度标记。一次HTTP不等于一条SQL；不全量拉全屏或等待最慢组件，静态bootstrap不混入高频全量时序。详见capacity-and-query-budget.md。

## Three.js最终选型补充 — 2026-10-29

用户已明确选定Three.js作为3D渲染引擎，不再是引擎候选；不自写底层渲染器，不整搬附件E3。采用受约束SceneJSON描述参数化轻量厂区，必要时配合优化GLB异形资产；stable deviceId/objectId与渲染instance映射独立，React页面状态和renderer生命周期隔离。卫星图仅为建模参考，不默认全球GIS；Cesium仅未来确有地理需求时评估。React Three Fiber（R3F）仅可选封装，尚未选定，不能标为用户决定。

2D地图仍为保底；选定Three.js不等于已实现全3D或已达性能。200并发、50路分析、16路显示仍待测，不购买地图、不复制许可不明的附件实现、不编码或部署。
