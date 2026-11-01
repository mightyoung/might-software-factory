import { parseWallTime, toWallTime } from "./time";
import {
  findingSources,
  findingUrgencies,
  type FindingSource,
  type FindingUrgency,
} from "./finding";

/**
 * 上报表单的草稿。注意没有“隐患等级”“上报人”“租户”字段：
 * 等级由有权限人员依据适用规则确认（未配置规则时保持待判定），上报人和租户只来自服务端验证过的会话。
 */
export interface FindingDraft {
  source: FindingSource;
  description: string;
  location: string;
  /** 上海墙上时间，格式 YYYY-MM-DDTHH:mm（来自 datetime-local）；提交时由适配器换算为带 +08:00 的 ISO。 */
  foundAt: string;
  urgency: FindingUrgency;
  interimMeasure: string;
}

export type FindingDraftField = keyof FindingDraft;
export type FindingDraftErrors = Partial<Record<FindingDraftField, string>>;

/** 展示层的输入限制，是产品假设而非后端契约；服务端必须重新校验。 */
export const DRAFT_LIMITS = {
  descriptionMin: 5,
  descriptionMax: 500,
  locationMax: 100,
  interimMeasureMax: 300,
  /** 发现时间允许比当前时间晚的容差，吸收设备时钟的小偏差。 */
  futureToleranceMinutes: 5,
  /** 早于当前时间超过该时长按补报处理。 */
  backfillAfterHours: 24,
} as const;

/** 草稿里的时间是上海“墙上时间”（YYYY-MM-DDTHH:mm），见 domain/time.ts。保留这两个名字以减少调用方改动。 */
export function formatLocalDateTime(date: Date): string {
  return toWallTime(date);
}

/** 严格解析：格式不符或日期不存在（如 2 月 31 日）返回 null。 */
export function parseLocalDateTime(value: string): Date | null {
  return parseWallTime(value);
}

export function createEmptyDraft(now: Date): FindingDraft {
  return {
    source: "FIELD_OBSERVATION",
    description: "",
    location: "",
    foundAt: formatLocalDateTime(now),
    urgency: "NORMAL",
    interimMeasure: "",
  };
}

/** 后端按 Unicode 码点计长（emoji 等占一个）；不用 UTF-16 的 string.length。 */
export function codePointLength(value: string): number {
  let count = 0;
  for (const _ of value) count += 1;
  return count;
}

/**
 * 与契约 location 的 pattern 一致：至少有一个码点不在 Unicode White_Space 内
 * （U+0009–000D、0020、0085、00A0、1680、2000–200A、2028、2029、202F、205F、3000）。
 * JS 的 trim() 不去除 U+0085，所以不能只靠 trim 后长度判断。
 */
const VISIBLE = /[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]/;
export function hasVisibleCharacter(value: string): boolean {
  return VISIBLE.test(value);
}

export function normalizeDraft(draft: FindingDraft): FindingDraft {
  return {
    ...draft,
    description: draft.description.trim(),
    location: draft.location.trim(),
    interimMeasure: draft.interimMeasure.trim(),
  };
}

/** 发现时间明显早于当前时间：按补报处理，同时保存发现时间和上报时间，不改写任何一个。 */
export function isBackfill(foundAt: string, now: Date): boolean {
  const found = parseLocalDateTime(foundAt);
  if (!found) return false;
  return now.getTime() - found.getTime() > DRAFT_LIMITS.backfillAfterHours * 3_600_000;
}

export function validateFindingDraft(input: FindingDraft, now: Date): FindingDraftErrors {
  const draft = normalizeDraft(input);
  const errors: FindingDraftErrors = {};

  if (!findingSources.includes(draft.source)) errors.source = "请选择来源";
  if (!findingUrgencies.includes(draft.urgency)) errors.urgency = "请选择紧急程度";

  if (!hasVisibleCharacter(draft.description)) errors.description = "请填写隐患描述";
  else if (codePointLength(draft.description) < DRAFT_LIMITS.descriptionMin) {
    errors.description = `描述至少 ${DRAFT_LIMITS.descriptionMin} 个字，请写清楚看到了什么`;
  } else if (codePointLength(draft.description) > DRAFT_LIMITS.descriptionMax) {
    errors.description = `描述不能超过 ${DRAFT_LIMITS.descriptionMax} 个字`;
  }

  if (!hasVisibleCharacter(draft.location)) errors.location = "请填写所在位置或设备";
  else if (codePointLength(draft.location) > DRAFT_LIMITS.locationMax) {
    errors.location = `位置不能超过 ${DRAFT_LIMITS.locationMax} 个字`;
  }

  const found = parseLocalDateTime(draft.foundAt);
  if (!draft.foundAt) errors.foundAt = "请填写发现时间";
  else if (!found) errors.foundAt = "发现时间格式不正确";
  else if (found.getTime() - now.getTime() > DRAFT_LIMITS.futureToleranceMinutes * 60_000) {
    errors.foundAt = "发现时间不能晚于当前时间";
  }

  if (codePointLength(draft.interimMeasure) > DRAFT_LIMITS.interimMeasureMax) {
    errors.interimMeasure = `临时措施不能超过 ${DRAFT_LIMITS.interimMeasureMax} 个字`;
  }

  return errors;
}

export function hasDraftErrors(errors: FindingDraftErrors): boolean {
  return Object.keys(errors).length > 0;
}

/** 表单里字段出现的顺序，用于提交失败后把焦点移到第一个出错字段。 */
export const DRAFT_FIELD_ORDER: readonly FindingDraftField[] = [
  "source",
  "foundAt",
  "location",
  "description",
  "urgency",
  "interimMeasure",
];
