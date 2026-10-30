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
  /** 本地日期时间，格式 YYYY-MM-DDTHH:mm（来自 datetime-local）；时区解释待后端契约确定。 */
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

const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

export function formatLocalDateTime(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 严格解析：格式不符或日期不存在（如 2 月 31 日）返回 null。 */
export function parseLocalDateTime(value: string): Date | null {
  const match = LOCAL_DATE_TIME.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number) as [number, number, number, number, number];
  const date = new Date(year, month - 1, day, hour, minute);
  const roundTrips =
    date.getFullYear() === year &&
    date.getMonth() === month - 1 &&
    date.getDate() === day &&
    date.getHours() === hour &&
    date.getMinutes() === minute;
  return roundTrips ? date : null;
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

  if (draft.description.length === 0) errors.description = "请填写隐患描述";
  else if (draft.description.length < DRAFT_LIMITS.descriptionMin) {
    errors.description = `描述至少 ${DRAFT_LIMITS.descriptionMin} 个字，请写清楚看到了什么`;
  } else if (draft.description.length > DRAFT_LIMITS.descriptionMax) {
    errors.description = `描述不能超过 ${DRAFT_LIMITS.descriptionMax} 个字`;
  }

  if (draft.location.length === 0) errors.location = "请填写所在位置或设备";
  else if (draft.location.length > DRAFT_LIMITS.locationMax) {
    errors.location = `位置不能超过 ${DRAFT_LIMITS.locationMax} 个字`;
  }

  const found = parseLocalDateTime(draft.foundAt);
  if (!draft.foundAt) errors.foundAt = "请填写发现时间";
  else if (!found) errors.foundAt = "发现时间格式不正确";
  else if (found.getTime() - now.getTime() > DRAFT_LIMITS.futureToleranceMinutes * 60_000) {
    errors.foundAt = "发现时间不能晚于当前时间";
  }

  if (draft.interimMeasure.length > DRAFT_LIMITS.interimMeasureMax) {
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
