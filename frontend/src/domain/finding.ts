/** 与 contracts/openapi/finding-ledger.yaml 的 state 枚举一致。DISCOVERED 是尚未启动确认流程的旧数据。 */
export const findingStates = [
  "DISCOVERED",
  "PERSON_CONFIRM_PENDING",
  "SAFETY_CONFIRM_PENDING",
  "SAFETY_DISPATCH_PENDING",
  "RECTIFYING",
  "ACCEPTANCE_PENDING",
  "NOT_ESTABLISHED",
  "CLOSED",
] as const;

export type FindingState = (typeof findingStates)[number];

export const findingStateLabels: Record<FindingState, string> = {
  DISCOVERED: "已发现·待启动流程",
  PERSON_CONFIRM_PENDING: "待一人确认",
  SAFETY_CONFIRM_PENDING: "待安全确认",
  SAFETY_DISPATCH_PENDING: "待安全部门派单",
  RECTIFYING: "整改中",
  ACCEPTANCE_PENDING: "待安全验收",
  NOT_ESTABLISHED: "未成立",
  CLOSED: "已闭环",
};

/** 人工上报时可选的来源。“监测/算法”产生的待确认发现由系统创建，不在手工表单里选择。 */
export const findingSources = ["FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT"] as const;
export type FindingSource = (typeof findingSources)[number];
export const findingSourceLabels: Record<FindingSource, string> = {
  FIELD_OBSERVATION: "现场发现",
  INSPECTION: "检查发现",
  PERSON_REPORT: "人员举报",
};

export const findingUrgencies = ["NORMAL", "URGENT", "IMMEDIATE"] as const;
export type FindingUrgency = (typeof findingUrgencies)[number];
export const findingUrgencyLabels: Record<FindingUrgency, string> = {
  NORMAL: "一般",
  URGENT: "紧急",
  IMMEDIATE: "需立即处置",
};

export const workflowSteps = ["发现上报", "一人确认", "安全部门确认", "安全部门派单", "整改", "安全验收"] as const;

export interface FindingHistoryEntry {
  at: string;
  title: string;
  note: string;
}

export interface FindingRecord {
  /** 稳定的内部标识（契约里是 UUID）：用于路由、勾选和读取详情，不用于展示。 */
  id: string;
  /** 业务编号（契约里的 code）：展示给人看和按编号查询。 */
  code: string;
  description: string;
  location: string;
  state: FindingState;
  reportedAt: string;
  reporter: string;
  responsible: string;
  riskLabel: "待专业评估";
  round: number;
  history: readonly FindingHistoryEntry[];
  /** 以下字段只有通过上报表单创建的记录才有；发现时间与上报时间分开保存，补报不改写发现时间。 */
  foundAt?: string;
  source?: FindingSource;
  urgency?: FindingUrgency;
  interimMeasure?: string;
  backfill?: boolean;
}

export interface FindingAction {
  label: string;
  reason: string;
}

/** Actions stay disabled until a real adapter and backend authorization exist. */
export function getFindingActions(state: FindingState): FindingAction[] {
  const actionLabels: Record<FindingState, string[]> = {
    DISCOVERED: ["启动确认流程"],
    PERSON_CONFIRM_PENDING: ["确认发现", "退回补充"],
    SAFETY_CONFIRM_PENDING: ["安全确认", "退回第一确认"],
    SAFETY_DISPATCH_PENDING: ["分派整改"],
    RECTIFYING: ["提交整改证据"],
    ACCEPTANCE_PENDING: ["验收通过", "退回整改"],
    NOT_ESTABLISHED: [],
    CLOSED: [],
  };

  return actionLabels[state].map((label) => ({
    label,
    reason: "仅演示。接入后端并由服务端校验身份、职责、状态、版本、轮次和证据后才可操作。",
  }));
}

export function workflowCurrentIndex(state: FindingState): number {
  const indexByState: Record<FindingState, number> = {
    DISCOVERED: 0,
    PERSON_CONFIRM_PENDING: 1,
    SAFETY_CONFIRM_PENDING: 2,
    SAFETY_DISPATCH_PENDING: 3,
    RECTIFYING: 4,
    ACCEPTANCE_PENDING: 5,
    NOT_ESTABLISHED: 0,
    CLOSED: 5,
  };
  return indexByState[state];
}
