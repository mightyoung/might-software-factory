# G025 证据终结（finalization）设计草案

状态：**草案，未实现**。写于 2026-11-09，依据 V5 / V6 / V15 / V16 / V18 迁移与 `files/`、`evidenceupload/` 现有代码。
目的：让有 Linux + ClamAV 环境的人可以按此落地，并在动手前先对齐方案。文中标 **【待确认】** 的是我没有从代码里查实、实现前必须核对的点。

## 1. 要解决的问题

上传链路目前止于 `VERIFIED_PENDING_FINALIZATION`：字节已在不可变存储里，扫描证明（`file_ingestion_scan_proof`）和内容回执（`file_ingestion_content_receipt`）已落库，但**没有任何业务行引用这个文件**。整改提交（`remediation_submission`）要求每条证据都是 `READY` 的 `evidence_item` + `READY` 的 `file_object` + 未撤销的 `file_link`（见 V6 `validate_submission_evidence_snapshot`）。把前者变成后者，就是 G025。

终结要做的事（一个数据库事务内）：

1. 创建 `file_object`（`READY`）、`file_link`（`FINDING_EVIDENCE_ITEM`）、`evidence_item`（`READY`、`CONTENT`）。
2. `evidence_set.evidence_revision` 前进（V6 触发器在插入 `evidence_item` 时自动 +1，应用不能手动改）。
3. 上传意图 `VERIFIED_PENDING_FINALIZATION → FINALIZED`。
4. 不可变分配追加 `TRANSFER_FINALIZED` 事件（把配额从“待定”转为“已终结”，`retained_bytes` 守恒，`finalized_file_id` 指向新文件）。
5. 向用户返回新的 `evidenceRevision`，前端据此继续（提交整改证据）。

## 2. 现有数据库对此的限制（关键发现）

**当前数据库明确禁止终结，所以 G025 必须带一份新迁移（V25 起），不是只加应用代码。**

| 位置 | 现状 | 需要的变更 |
|---|---|---|
| V18 `check_finding_ingestion_transition` | `new.state='FINALIZED'` 直接抛 “Upload intent immutable declaration or version is invalid”；允许的转换里没有 `VERIFIED_PENDING_FINALIZATION → FINALIZED` | 新增这一条转换，且只在同一事务已创建对应 `file_object/file_link/evidence_item` 时放行 |
| V18 `check_file_ingestion_event` | 非 RETAIN 事件只允许 `RESIZE_CONFIRMED/RECOVERY_OBSERVED/DISPOSAL_CLAIMED/DISPOSAL_CONFIRMED`，其它抛 “publication or reduction is not enabled”；`TRANSFER_FINALIZED` 之后不能再追加事件 | 新增 `TRANSFER_FINALIZED` 分支：只作用于 `IMMUTABLE` 分配、`retained_bytes` 与前一事件相等、`finalized_file_id` 非空且等于新建的 `file_object.id`、意图在 `VERIFIED_PENDING_FINALIZATION` |
| V18 头注释 | “Technical ingestion never publishes READY metadata, evidence items, links, or workflow commands.” | 终结是**业务事务**，不属于技术摄取；新触发器应要求调用来自终结路径（见 §5） |
| V6 `evidence_set` 状态 | 枚举 `UPLOADING/QUARANTINED/READY/FAILED`；`evidence_set_ready_shape`：`READY` 需 `verified_at`；守卫触发器规定改状态不能和 revision 前进同时发生 | 集合何时变 `READY` 要定（见 §7 问题 2） |
| V6 `evidence_item` 触发器 | 每次 INSERT/UPDATE 会 +1 `evidence_revision`；已封存的集合（`submission_sealed_at`）拒绝 | 终结时一个意图插一条 item，revision 恰好 +1，可直接利用 |
| V6 `validate_submission_evidence_snapshot` | 提交时要求：`source_kind='FILE'`、item `READY`、`verification_level='CONTENT'`、set `READY`、file `READY` 且有 `verified_at`、`evidence_item.sha256 = file_object.checksum`、`file_object.storage_version` 一致、文件属主 = 提交人、link 未撤销 | 终结写入时就要满足这些，否则提交会失败 |

## 3. 新迁移草案（V25，仅 DDL/触发器，需单独授权才能应用到 NAS）

- 放宽 `check_finding_ingestion_transition`：增加分支
  `old.state='VERIFIED_PENDING_FINALIZATION' and new.state='FINALIZED'`，
  `allowed = ['state','technical_version','technical_updated_at']`，要求：
  - `old.cancel_requested` 为假，`old.expires_at > statement_timestamp()`（过期不能终结）；
  - `scan_proof` 与 `content_receipt` 复用 V18 `SCANNING → VERIFIED_PENDING_FINALIZATION` 里同一组校验（`proof.expires_at`、`receipt.expires_at` 均在未来；`receipt.scan_proof_id = proof.id`；`receipt.immutable_allocation_id` = 意图的 `immutable_allocation_id`）。这组校验在时间上可能已过期，**终结前必须能拒绝过期证明**；
  - 存在恰好一条 `evidence_item`（`file_id` 对应的 `file_object` 的 `checksum = receipt.sha256`、`size_bytes = receipt.size_bytes`、`storage_version = receipt.storage_version`）。
- 新增 `TRANSFER_FINALIZED` 事件校验（见 §2）。
- （可选）为 `file_object.object_key` 增加“必须等于不可变分配的 `server_object_name`”的校验。**【待确认】** `object_key` 的取值规则：`FindingEvidenceContentReadService` / `JooqFindingEvidenceContentReadRepository` 是用哪个字段去读字节的，必须保持一致，否则读不到文件。
- 授权：运行时角色需要对 `file_object`、`file_link`、`evidence_item` 的 `INSERT`（`evidence_item` 还需要 `UPDATE`？见 §7 问题 3）；`evidence_set` 的 `UPDATE`（若需要置 `READY`）。对应补到 `docs/pg18-finding-evidence-integration-checklist.md`。

## 4. 接口契约（OpenAPI 草案，`finding-evidence-upload.yaml`）

```
POST /api/v1/findings/{findingId}/remediation-rounds/{roundNo}/evidence-uploads/{uploadId}/finalize
Headers: Idempotency-Key (uuid, 必填)  ← 与现有 reserve 接口一致
Body:    { "expectedEvidenceRevision": <long>, "expectedFindingVersion": <long> }
```

- 只有**该上传的属主**可调用（与取消、读取一致）；他人 / 不可见 → 404。
- 成功 `200`：返回
  `{ uploadId, state: "FINALIZED", evidenceItemId, evidenceRevision }`；
  不返回文件名、哈希、路径、对象键。
- 同一 `Idempotency-Key` 重放返回同一结果（需要一张终结回执表，见 §6）。
- 错误：
  - `404` 不可见；
  - `409` `expectedEvidenceRevision` / `expectedFindingVersion` 过期（带最新值）；
  - `409` 上传不在 `VERIFIED_PENDING_FINALIZATION`（带当前状态），包括已被取消 / 过期 / 已终结（已终结 + 同键 = 幂等重放，不同键 = 409）；
  - `422` 扫描证明或内容回执已过期，意图回到 `RETRY_WAIT` 重新验证（沿用 V18 的 `VERIFIED_PENDING_FINALIZATION → RETRY_WAIT`）；
  - `503` 存储 / 数据库不可用（按现有 `unavailable` 处理，已带日志）。
- 响应头 `Cache-Control: no-store`。
- 状态读取接口（`GET .../evidence-uploads/{id}`）新增 `FINALIZED` 状态展示；`GET .../evidence-set` 的 `uploads` 保持不变，`evidenceRevision` 已是最新值。

## 5. 事务边界与顺序（单个数据库事务）

文件字节的复制、扫描、不可变发布**已经在事务外完成**，终结事务只写元数据，不碰存储。

1. 锁：`select … from finding_evidence_upload_intent … for update`（V18 事件触发器也是这么锁的）；再锁 `evidence_set … for update`。固定先意图后集合，避免与取消 / 过期扫描死锁。
2. 授权与上下文：当前用户 = `owner_member_id`；整改轮次、通知仍属于该用户；`expected_finding_version` 仍匹配（若轮次已前进，则 `409`）。
3. 校验：状态 = `VERIFIED_PENDING_FINALIZATION`；`cancel_requested` 为假；`expires_at` 未到；`scan_proof`、`content_receipt` 未过期且互相指向一致；`evidence_set.submission_sealed_at is null`；集合当前 `evidence_revision = expectedEvidenceRevision`。
4. 插入 `file_object`（`state='READY'`，`checksum = receipt.sha256`，`size_bytes`，`mime = receipt.detected_media_type`，`storage_version`，`verified_at = now`，`owner_member_id/owner_actor_id` = 意图属主）。
5. 插入 `file_link`（`resource_type='FINDING_EVIDENCE_ITEM'`，`resource_id = 新 evidence_item.id`，`purpose` **【待确认】** 取值，需与 `JooqFindingWorkflowEvidencePolicy` / 内容读取侧一致）。
6. 插入 `evidence_item`（`source_kind='FILE'`，`state='READY'`，`verification_level='CONTENT'`，`sha256 = receipt.sha256`，`captured_at = now`）。触发器使 `evidence_revision + 1`。
7. 追加 `TRANSFER_FINALIZED` 分配事件（`retained_bytes` 不变，`finalized_file_id = 新文件 id`）。
8. 意图 `VERIFIED_PENDING_FINALIZATION → FINALIZED`，`technical_version + 1`。
9. 写终结回执（幂等键 → 结果）。
10. 提交。任何一步失败整体回滚，意图保持 `VERIFIED_PENDING_FINALIZATION`，调用方可重试。

不要在事务内：读写文件系统、调用扫描器、发 HTTP。

## 6. 幂等与并发

- **同键重放**：新增 `finding_evidence_finalization_receipt` 表（`tenant_id, owner_actor_id, idempotency_key, upload_id, evidence_item_id, resulting_revision`，不可变触发器，FORCE RLS），仿 V16 `finding_evidence_set_initialization_receipt`。
- **并发终结同一上传**：意图行 `for update` 串行化；第二个看到 `FINALIZED` → 按回执返回同一结果或 `409`。
- **并发终结同集合的两个上传**：集合行 `for update` 串行化；第二个的 `expectedEvidenceRevision` 已过期 → `409`，前端重新读取 `GET .../evidence-set` 后重试（D25 已为此铺路）。
- **终结与取消 / 过期并发**：均先锁意图行，状态检查决定谁赢；失败方得到 `409`。
- **终结与整改提交并发**：提交会封存集合（`submission_sealed_at`）。终结在集合行锁内检查未封存，已封存 → `409` 且不产生任何行。

## 7. 待决问题（实现前需要确认）

1. **一个集合允许几条证据？** 现有 `evidence-set` 读取接口返回多个上传，意味着一个集合可含多条终结证据。若是，每次终结都 +1 revision，前端需要在多次终结之间刷新版本。请确认业务上限（V15 读取接口目前上限 100 个意图）。
2. **`evidence_set.state` 何时变 `READY`？** V6 校验要求提交时集合为 `READY`，但守卫触发器规定“改状态”和“改 revision”不能同次发生，且封存是另一条路径。候选：由整改提交命令负责（提交前先置 `READY`），或终结最后一步单独 `UPDATE state='READY', verified_at=now`（独立语句，且此时 revision 已稳定）。我倾向前者以避免终结与提交耦合，但需要对照 `JooqFindingWorkflowRepository` 里现有提交代码确认它是否已经处理 `READY`。
3. **`evidence_item` 是否需要 `UPDATE` 权限？** 若由一条 INSERT 直接写成 `READY`，只需要 `INSERT`；V6 的 `UPDATE` 守卫存在是为了以后的状态变更。按最小权限只授予 `INSERT`。
4. **`file_link.purpose` 和 `file_object.object_key` 的规则**：见 §3、§5。
5. **配额**：`TRANSFER_FINALIZED` 把 `owner_pending` 转为 `owner_finalized`。终结后是否还需要给已终结字节设上限？当前 `PhysicalCharges` 只统计，不限制；沿用即可，但请确认。
6. **过期证明的处理**：证明有效期短（`expires_at`）。用户在“待终结”停留过久，终结时证明已过期，必须重新验证（`→ RETRY_WAIT`）而不能放行。前端文案需要新增“证明已过期，正在重新验证”。
7. **审计**：终结是否需要在 `tenant_audit` / 流程轨迹写一条事件？`TRANSFER_FINALIZED` 属于文件账本，不等于业务审计。**【待确认】** 现有审计能力（V23）的事件类型。

## 8. 测试要求

- **数据库层（需要外部基础库，V1/V9/V10 不在仓库）**：触发器新增分支的正反用例——证明过期、回执不匹配、集合已封存、重复终结、`retained_bytes` 不守恒、`finalized_file_id` 与新文件不一致、非属主、跨租户。
- **应用层**：`FindingEvidenceFinalizationServiceTest`——授权、状态机、`expected*` 冲突、幂等重放、并发终结（两个线程）。
- **HTTP 层**：`FindingEvidenceUploadControllerHttpTest` 增加终结接口：404/409/422/503 与无缓存头。
- **契约**：`FindingEvidenceUploadRequestParserTest` 的 OpenAPI 路径集合加入 `/finalize`。
- **端到端（唯一能证明整条链路的测试）**：真实扫描器 → 上传 → 终结 → 提交整改。**这一项在 NAS 装好 ClamAV 1.4.6 + root 管理的隔离配置之前无法执行**，所以实现可以先合入但必须标注“未做端到端验证”，并且前端“提交整改证据”按钮保持禁用，直到联调通过。

## 9. 前端配合

- `FindingEvidenceAdapter` 增加 `finalize(scope, uploadId, expected, signal)`；HTTP 适配器与 mock 服务器同步；`npm run gen:api` 重新生成类型。
- `useEvidenceUpload`：上传到 `VERIFIED_PENDING_FINALIZATION` 后自动（或由用户点“确认使用此证据”）调用终结；成功后以返回的 `evidenceRevision` 更新内存里的集合版本。
- 面板文案：新增“已采用”状态；`422 证明已过期` 时显示“正在重新验证”并继续轮询。
- 取消在 `FINALIZED` 之后不可用（后端对已终结上传的取消应 `409`，**【待确认】** 现有取消实现对 `FINALIZED` 的行为）。

## 10. 建议的落地顺序

1. 对齐 §7 的待决问题（尤其 1、2、4）。
2. V25 迁移 + 数据库层测试（需要有完整基础库的环境）。
3. 契约（OpenAPI）→ 后端服务 / 控制器 / 仓库 + 单元与 HTTP 测试。
4. 前端适配器、mock、面板。
5. 装好扫描器后做端到端联调，再放开“提交整改证据”。
