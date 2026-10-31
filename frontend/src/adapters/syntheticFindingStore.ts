import { listFindingRecords, syntheticFindingId, syntheticFindings, type FindingLedgerAdapter } from "./findingLedger";
import { FindingSubmitError, type FindingSubmitAdapter, type FindingSubmitResult } from "./findingSubmit";
import type { FindingRecord } from "../domain/finding";
import {
  hasDraftErrors,
  isBackfill,
  normalizeDraft,
  validateFindingDraft,
} from "../domain/findingDraft";
import { toIsoWithOffset, toWallTime, wallTimeToIso } from "../domain/time";

export interface SyntheticFindingStore {
  ledger: FindingLedgerAdapter;
  submit: FindingSubmitAdapter;
}

export interface SyntheticFindingStoreOptions {
  seed?: readonly FindingRecord[];
  now?: () => Date;
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
}

function nextSequence(records: readonly FindingRecord[]): number {
  return records.reduce((max, record) => {
    const match = /^DEMO-HZ-(\d+)$/.exec(record.code);
    return match ? Math.max(max, Number(match[1])) : max;
  }, 0);
}

/**
 * 内存中的合成隐患存储：列表读取和上报提交共享同一份数据，所以新上报的记录能在台账里看到。
 * 不读写浏览器存储、不联网，刷新页面即恢复为种子数据；不通知任何人，也不形成真实记录。
 * 幂等：同一幂等键 + 同一内容返回同一结果、不再新建；同键不同内容拒绝为 conflict。
 */
export function createSyntheticFindingStore(options: SyntheticFindingStoreOptions = {}): SyntheticFindingStore {
  const now = options.now ?? (() => new Date());
  let records: readonly FindingRecord[] = [...(options.seed ?? syntheticFindings)];
  let sequence = nextSequence(records);
  const processed = new Map<string, { payload: string; result: FindingSubmitResult }>();

  const ledger: FindingLedgerAdapter = {
    async list(input) {
      return listFindingRecords(records, input);
    },
    async get(id) {
      return records.find((record) => record.id === id) ?? null;
    },
  };

  const submit: FindingSubmitAdapter = {
    async submit(input, { idempotencyKey, signal }) {
      throwIfAborted(signal);
      const draft = normalizeDraft(input);
      const payload = JSON.stringify(draft);

      const previous = processed.get(idempotencyKey);
      if (previous) {
        if (previous.payload !== payload) throw new FindingSubmitError("conflict");
        return previous.result;
      }

      // 服务端必须独立于界面重新校验。
      const errors = validateFindingDraft(draft, now());
      if (hasDraftErrors(errors)) throw new FindingSubmitError("validation", errors);

      const submittedAt = now();
      const backfill = isBackfill(draft.foundAt, submittedAt);
      sequence += 1;
      const id = syntheticFindingId(sequence);
      const code = `DEMO-HZ-${String(sequence).padStart(3, "0")}`;
      const stamp = toWallTime(submittedAt);
      const record: FindingRecord = {
        id,
        code,
        description: draft.description,
        location: draft.location,
        state: "PERSON_CONFIRM_PENDING",
        reportedAt: toIsoWithOffset(submittedAt),
        reporter: "本人（演示）",
        responsible: "待分派",
        riskLabel: "待专业评估",
        round: 1,
        history: [
          {
            at: stamp.replace("T", " "),
            title: "发现上报",
            note: `合成演示：由你在本页创建${backfill ? "（补报：发现时间与上报时间分别保存）" : ""}；未通知任何人，也不形成真实记录。`,
          },
        ],
        foundAt: wallTimeToIso(draft.foundAt) ?? draft.foundAt,
        source: draft.source,
        urgency: draft.urgency,
        interimMeasure: draft.interimMeasure || undefined,
        backfill,
      };
      records = [record, ...records];

      const result: FindingSubmitResult = {
        id,
        code,
        state: record.state,
        submittedAt: record.reportedAt,
        backfill,
      };
      processed.set(idempotencyKey, { payload, result });
      return result;
    },
  };

  return { ledger, submit };
}

export const syntheticFindingStore: SyntheticFindingStore = createSyntheticFindingStore();
