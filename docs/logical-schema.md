# 逻辑数据模型与约束基线 v1.0

状态：可实施级逻辑设计，非DDL或已实现数据库；2026-10-29。本规范补齐公共服务、双预防、算法事件组合的数据契约。真实行业专项阈值仍不得伪填。

## 1 全表约定
类型：ID=uuid；时间=timestamptz(UTC)；代码varchar(64)，名称varchar(200)，说明text有API长度上限；精确值numeric(20,6)，禁止用float存金额/专业阈值；版本bigint。下文字段无?为NOT NULL，?为可空。每个可变租户聚合含id、tenant_id、version>=1、created_at、updated_at、created_by、updated_by；created_by/updated_by为actor主体引用，后台主体也有明确身份。事件/审计不可变记录无更新列。

每个租户实体建UNIQUE(tenant_id,id)，所有租户内关系FK(tenant_id,ref_id)→目标(tenant_id,id)。关联同tenant由数据库约束而非仅代码。全局account/tenant/平台应用单独表，不伪造公共tenant=0。主键不可改；业务code唯一且稳定，不以名称做关联。默认FK ON DELETE RESTRICT；仅明确聚合明细可受控级联，证据/审计/独立资产禁止误级联。状态CHECK限制白名单，状态转移由服务命令验证。

删除默认停用/归档，deleted_at?仅用于明确软删实体；唯一code原则不随软删释放，避免历史指向歧义。history/版本表追加不可覆盖。秘密仅secret_ref，口令哈希在身份专表；JSON扩展设schemaVersion、大小/深度限制，不保存可执行表达式。

## 2 公共数据
|表|业务字段（省略全表约定）|约束与关系|
|---|---|---|
|account（全局）|login_key varchar(200), status, credential_version bigint|login_key规范化唯一；状态ACTIVE/LOCKED/DISABLED；外部身份不按邮箱自动合并|
|credential（全局）|account_id uuid, kind, secret_hash text, changed_at|FK account；不存明文；每种当前凭证受唯一约束|
|external_identity（全局）|issuer text, subject text, account_id uuid|UNIQUE(issuer,subject)，长度受API限制|
|tenant|code,name,status,auth_epoch bigint|code唯一；ACTIVE/SUSPENDED；平台作用域|
|tenant_member|account_id uuid,status,display_name,auth_epoch bigint|UNIQUE(tenant_id,account_id)，account为全局FK；成员安全状态独立于租户auth_epoch|
|org_unit|code,name,parent_id?,active boolean|租户code唯一；同级有效name唯一；parent同租户，服务递归防环并发锁|
|member_org|member_id,org_id,kind,valid_from,valid_to?|from<to；PRIMARY/CONCURRENT；有效期同成员PRIMARY不得重叠，事务校验并锁成员|
|position/group|code,name,active|租户code唯一；岗位可绑定org_id?|
|member_position/group_member|member_id,target_id,valid_from,valid_to?|同租户FK、期限合法；相同成员目标有效期不得重叠|
|permission（平台）|code,resource_type,action|code唯一，随版本发布；不能租户随意创造平台权限|
|role|code,name,active|租户code唯一|
|role_permission|role_id,permission_id,scope_rule_id?|UNIQUE(tenant_id,role_id,permission_id)，scope绑定特定动作|
|role_grant|role_id,subject_kind,member_id?/org_id?/position_id?/group_id?,source_type,source_id,valid_from,valid_to?|CHECK对应kind恰有一个subject非空；每个引用tenantFK；来源不采用无校验自由ID|
|scope_rule|kind,version_no,definition jsonb|仅类型白名单AST；SELF/DEPT/DEPT_TREE/EXPLICIT/RELATION；org集合用scope_org明细FK|
|tenant_application|application_id,status,config_version|UNIQUE(tenant_id,application_id)；不等于用户许可|
|entity_schema/action_schema（发布元数据）|app_key,key,schema_version,content jsonb,checksum|UNIQUE(app_key,key,schema_version)；不可变发布包，不在线DDL|
|saved_view|member_id,entity_key,schema_version,filter_ast jsonb,sort_spec jsonb,columns jsonb,name|所有field须存在/可见；schema升级校验无效条件，不能静默扩大范围|
|file_object|tenant、id、owner_member_id、owner_actor_id、object_key,size_bytes bigint,mime,checksum?,state,storage_version,verified_at?|size>=0，tenant内object_key唯一；摘要绑定存储版本，READY要求服务端校验完成|
|file_link|tenant、file_id,resource_type,resource_id,purpose,linked_by_actor_id,revoked_at?|目标经注册资源解析器验证tenant与存在性；多态关联不假装有跨表FK，撤销后拒绝访问，定期一致性检查|
|dictionary_item|dictionary_key,code,label,sort_no int,active,version_no|UNIQUE(tenant_id,dictionary_key,code)，引用稳定ID|
|config_entry|scope,key,value_type,value_json?,secret_ref?,version_no|值与secret_ref互斥，类型schema校验；覆盖白名单|
|audit_event|actor_id,delegator_id?,action,resource_type,resource_id?,result,reason?,diff_redacted jsonb?,trace_id,policy_version,occurred_at|只追加；不存凭证/完整敏感正文|
|idempotency_record|actor_id,operation,key,payload_hash,state,result_ref?,created_at,expires_at?|UNIQUE(tenant_id,actor_id,operation,key)；业务关键键保留期≥业务重试/回放窗口|

## 3 空间 设备与双预防
|表|关键字段|约束|
|---|---|---|
|workshop|code,name,boundary_json?,responsible_org_id|空间非组织树，boundary带坐标系版本|
|production_line|code,name,workshop_id|主归属同tenant；跨工房用line_workshop明细|
|process_stage|line_id,code,name,ordinal int|UNIQUE(tenant,line_id,code)，ordinal不代表算法顺序|
|equipment|code,name,type,state,location_ref?|独立生命周期；code租户唯一|
|stage_equipment|stage_id,equipment_id,valid_from,valid_to?|多对多；同设备可多环节，禁止误设equipment单归属|
|responsibility_binding|object_type,object_id,org_id,position_id?,duty,valid_from,valid_to?|多态目标注册校验；历史责任快照单存|
|hazard_source|code,work_unit_id,description,factor_code_ref?,factor_version?,identified_at,status|代码/版本成对；未取得标准字典允许未知，不伪填|
|risk_scenario|code,description,consequence,accident_code_ref?,accident_version?|risk_hazard明细多对多；无评估不默认低风险|
|assessment|scenario_id,revision,method_ref,input jsonb,grade?,status,effective_at?,reviewer_id?|UNIQUE(tenant,scenario,revision)，已发布不可改；缺输入grade空/待评估|
|control_measure|code,description,category,responsible_member_id?,responsible_position_id?,state|责任解析至少一种可用；risk_control明细|
|checklist_version|key,revision,state,content_hash|发布不可变；items存typed criteria/来源/证据要求|
|inspection_task|template_version_id,scope_ref,assignee_id,due_at,state|每次生成稳定generation_key唯一|
|inspection_result|task_id,item_id,result_kind,value_json?,evidence_ref?,checked_at?|UNIQUE(tenant,task,item)，UNINSPECTED不等于PASS|
|finding|code,description,discovered_at,source_event_id?,risk_id?,grade,state,round_no int|grade UNKNOWN/GENERAL/MAJOR，与alarm_severity独立；source关联不强制必须有risk|
|finding_confirmation|finding_id,stage,actor_member_id,decision,reason?,at,expected_version|stage PERSON/SAFETY，分别留痕；不能省PERSON|
|remediation_notice|finding_id,revision,round_no,assignee_id,due_at,original_due_at,requirements,state,issued_at?,delivered_at?|版本追加，派单与送达区分|
|remediation_submission|notice_id,round_no,submitted_by,submitted_at,evidence_set_id,statement,evidence_snapshot_count?,evidence_snapshot_manifest?,evidence_snapshots_sealed_at?|一次提交版本化；只能在事务内由未封存转成封存，数据库计算完整清单/count并以延迟约束拒绝未封存提交，封存后禁止修改或增补|
|verification|finding_id,submission_id,round_no,reviewer_id,decision,reason,at|PASS/FAIL，默认reviewer!=整改人；FAIL要求原因并回整改|
|finding_transition|finding_id,from_state,to_state,command_id,actor_id,at,reason?|UNIQUE(tenant,command_id)，不可变历史|
|report_record|finding_id?,type,recipient,deadline?,submitted_at?,receipt_file_id?,status|UNKNOWN/PENDING/SUBMITTED/ACKNOWLEDGED/FAILED，内部审批不代监管回执|
|source_rule_version|key,revision,source_url,document_version,effective_from?,effective_to?,verification_level,applicability jsonb|未全文核验禁止标FULL_TEXT；未知日期允许空并标unknown|
|major_hazard_assessment|object_ref,rule_version_id,status,input_evidence,conclusion?|UNKNOWN/NOT_APPLICABLE/YES/NO，独立专业档案|

聚合边界：finding主记录+本次确认/状态事件同事务；整改提交+附件绑定验证+状态同事务。外部附件字节先上传检验，不能在该事务内跨系统写文件。流程定义/任务来自公共引擎，业务状态投影只有唯一WorkflowFacade命令维护，不能两套状态各自自动流转。

## 4 数据 算法 事件
|表|字段|约束|
|---|---|---|
|data_source|code,kind,equipment_id?,channel,adapter_version,secret_ref?,state,last_seen_at?|端点允许列表；source不以URL作为公开ID|
|region_version|source_id,key,revision,geometry jsonb,width int,height int,transform_version,valid_from,valid_to?|width,height>0；坐标边界校验；同源坐标版本绑定|
|processing_chain_version|key,revision,steps jsonb,input_schema,output_schema,quality_contract_ref|仅注册处理器/有向无环图，禁止任意代码|
|algorithm_version|service_key,version,input_schema,output_schema,model_digest?,capabilities jsonb|UNIQUE(tenant,service_key,version)，声明不等于效果验证|
|execution_binding_version|key,revision,source_id,chain_version_id,algorithm_version_id,region_version_id?,schedule_spec,config_hash|全部同tenant引用；已发布不可改|
|execution_run|binding_version_id,logical_run_key,status,deadline_at,cancel_requested_at?,started_at?,finished_at?,attempt int|UNIQUE(tenant,binding_version_id,logical_run_key)，重试同逻辑键|
|observation|source_id,source_seq?,event_at?,received_at,value_json,unit?,quality_flags jsonb,raw_ref?,mapping_version|event_at未知不得伪造；无源序号不随意按数值去重|
|algorithm_result|run_id,result_id,source_id,stream_session_id?,frame_time?,produced_at,received_at,status,payload,quality_flags,evidence_ref?|UNIQUE(tenant,run_id,result_id)，输入/配置版本可追溯|
|domain_event|event_id,type,subject_type,subject_id,occurred_at,received_at,payload_schema,payload,causation_id?,correlation_id,root_event_id,depth int,origin_system,actor_id?,rule_version?|event_id租户唯一，深度非负；来源与台账动作结果分离|
|trigger_rule_version|key,revision,source_type,condition_ast,action_specs,limits,status|AST类型校验；动作注册白名单，不SQL/脚本|
|trigger_execution|rule_version_id,event_id,action_key,ordinal,state,payload_hash,result_ref?|UNIQUE(tenant,rule_version,event_id,action_key,ordinal)|
|alarm_episode|rule_version_id,object_id,region_id?,condition_key,condition,acknowledged_at?,acknowledged_by?,suppressed_until?,suppression_reason?,first_seen,last_seen,recovered_at?,count bigint|活动identity hash+tenant部分唯一，允许恢复后新周期|
|notification_delivery|event_id,recipient_id,channel,state,attempt,provider_ref?,last_error?|UNIQUE(tenant,event_id,recipient_id,channel)，UNKNOWN可查单|
|tracking_identity|source_id,stream_session_id,local_track_id,first_seen,last_seen|组合唯一；global_reid_candidate另表有证据/模型/修正历史|

## 5 索引策略
每张tenant业务表先建(tenant_id,id)唯一；列表按实际过滤组合加(tenant_id,state,updated_at DESC,id)、(tenant_id,responsible_member_id,due_at,id)。finding等级状态索引用(tenant_id,grade,state,updated_at,id)；到期查询联接当前通知书(tenant_id,assignee_id,state,due_at,id)，不引用finding中未声明的due_at列。org parent、成员有效授权、各FK反向查询建索引。大关联表UNIQUE(tenant_id,left_id,right_id)或含版本期限。

事件/结果按(tenant_id,subject_id,occurred_at DESC,id)/(tenant_id,run_id)；任务领取用(state,next_run_at,lease_until)的待执行部分索引；审计按(tenant_id,occurred_at DESC,id)，高量后按时间分区，不首期承诺规模。GIN只对已证实有查询需求的json路径加，不给所有JSON建大索引。模糊搜索先限字段/输入长度/返回数，trigram扩展需评估而非默认全库模糊扫。所有索引以代表数据EXPLAIN/写入开销验收，非已压测最优配置。

## 6 扩展和迁移
高频过滤/关系/状态/租户放稳定类型列，不把核心全部EAV/JSON。业务可声明扩展JSON schema，发布前兼容检查；新增必填字段先nullable→分批回填→验证→NOT NULL，禁止旧行无默认直接失败。金额/单位/身份字段变化须迁移规则和历史保留，不能自动转型猜测。

迁移ID V{递增号}__{模块_目的}，发布清单锁checksum、最低/目标应用版本、回填/锁表影响、验证与恢复方案；引擎升级脚本单列纳入同发布审查。已执行脚本不可修改。应用先兼容新旧schema，破坏性收缩在旧实例退出后做。数据回填分批断点、幂等、可监控，失败不继续发布。API/schema/event版本和DDL版本分别管理，不假设版本号相同。

本文件是逻辑约束基线，不代表DDL已部署。finding 只读切片的迁移脚本已形成：V2 finding schema 只在专用测试库执行，V3 身份/目录/授权脚本仍未应用到任何数据库。其他模块的迁移需按阶段形成，遵守本逻辑模型并通过相应测试验证。

## 7 补充支撑实体与边界
|表|字段/约束|
|---|---|
|principal|全局id、kind(HUMAN/SERVICE/AGENT)、account_id?、service_key?、status；kind与对应身份互斥；历史actor不要求仍为有效成员|
|workflow_binding|tenant、business_type/business_id/business_revision、submission_no、definition_id、instance_id；UNIQUE(tenant,business_type,business_id,submission_no)，engine实例引用唯一；重提不被business_id永久唯一阻止|
|remediation_round|tenant、finding_id、round_no、state、opened_at、closed_at?；UNIQUE(tenant,finding_id,round_no)，通知书/提交/验收FK关联round|
|job|tenant、type、payload_version、payload、state、next_run_at、lease_owner?、lease_until?、attempt、last_error?、result_ref?；logical_key唯一，状态含UNKNOWN/CANCEL_REQUESTED|
|session租户上下文|Session JDBC使用其官方schema；会话标识不入业务URL。member/tenant auth_epoch独立校验，不能依赖整条session删除解决多租户撤权|
|evidence_set/evidence_item|set_id、finding_id、round_no、owner_member_id、file_id?/source_url?、captured_at?、verified_at?、verification_level、hash?、rule/algorithm版本引用；证据集另有单调递增的evidence_revision与单向submission_sealed_at；整改提交只可使用同隐患/轮次且归属于提交人的证据集，所有项均须READY且CONTENT核验；FILE须有同租户file_object与指向该item的有效file_link，且可信服务端核验回执须绑定文件摘要/存储版本；表内CONTENT字段只是投影，不是信任来源；未定义共享授权前拒绝跨成员复用；URL在可信核验器接入前拒绝。证据项插入和非身份字段更新先递增所属证据集revision，身份键tenant/id/set不可变；业务运行路径禁止硬删除证据项，V6不提供删除绕过，任何保留/隐私例外须另行设计和审查。提交封存也递增revision并置sealed标记；两条路径更新同一父行，使旧REPEATABLE READ快照在父行冲突，而非依赖默认READ COMMITTED。封存后证据集和证据项不可修改。|
|remediation_submission_evidence|tenant/submission/finding/round/evidence_set/submitted_by、evidence_item_id、file_link_id、file_id、owner_member_id/actor_id、sha256、storage_version、receipt_id/verifier/key/policy/verified_at；提交事务内写入只追加快照，数据库对submission行与evidence_set父版本行执行真实更新并持锁，证据项增删改也先递增同一父版本；比较全部item ID的有序manifest后计算count并分别单向封存submission与evidence_set，避免依赖默认READ COMMITTED；后续验收核对封存count/manifest并按receipt_id验证，文件读取必须按快照中的immutable storage_version，禁止回退到latest；无存储版本读取保证时拒绝|同一证据集重复引用同一file仍可保存为不同item，不加file_id唯一约束|
|event血缘附字段|schema_version、source_kind、replay_run_id?、quality_flags；因果引用若来源外部允许external_ref，进入本系统后有稳定eventId|
|notification_message|独立notificationId、触发eventId、templateVersion、purpose；delivery唯一(notificationId,recipient,channel)，升级/恢复产生新notificationId，不被原事件键误吞|

根组织同级名称唯一采用PostgreSQL NULLS NOT DISTINCT唯一索引或等价受测策略，普通UNIQUE(tenant,parent,name)不能覆盖NULL根节点。可空字段不以空串替代。org路径/closure作为读优化时由directory模块事务维护，不作为第二真相。

来源授权subject采用上表四个分型FK并CHECK恰一非空；source若可引用多实体，定义专门grant_source(kind, stable_key, linked_ref)及来源解析器，不假装任意source_id已有数据库FK。每种source发布前须注册撤销逻辑。role_grant不允许用户伪造来源类型。

审计、事件、规则/流程历史、证据版本不随聚合级联；只读归档/保留期清理也需记录。外部提供者结果ID另以provider_id命名空间唯一，不能假设不同算法resultId全球唯一。设备/环节期限关联增加UNIQUE(tenant,stage,equipment,valid_from)，并核有效期重叠策略。

## 8 地图大屏扩展与现行命名
新增spatial_node、map_definition/map_version/map_placement/map_hotspot/map_video_overlay、screen_definition/screen_version/screen_widget、dataset_registration/metric_registration及发布审计的字段约束见[设备地图大屏第10至15节](device-map-dashboard.md)。复用equipment/data_source/region_version/domain_event/file_object，禁止复制资产、视频ROI和报警事实。报警字段按下述唯一维度规范。

## 报警维度规范
alarm_episode.condition为PENDING/ACTIVE/RECOVERED/UNKNOWN，非空；ACKNOWLEDGED和SUPPRESSED不再是condition值。acknowledged_at与acknowledged_by同时为空或同时非空，成员引用使用tenant复合FK；确认动作不改condition。suppressed_until与suppression_reason同时为空或同时非空；抑制仅控制已声明通知/显示策略，不删除事实或伪造恢复。恢复只由有效恢复证据驱动，数据断流转UNKNOWN而非RECOVERED；恢复时间必须与恢复事件关联。condition=RECOVERED时recovered_at非空，其他当前条件不写新的恢复时间，历史变化由事件保留。活动identity唯一约束覆盖PENDING/ACTIVE/UNKNOWN，抑制或确认不释放唯一键；恢复后可新开episode。旧单enum数据迁移必须按历史证据拆维度，缺恢复证据的ACK不得迁成RECOVERED；迁移回填和负例测试后才切读。
