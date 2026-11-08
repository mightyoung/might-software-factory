import type { FindingCommand, FindingCommandFailureKind, FindingReviewHistory as ReviewHistory } from "../../adapters/findingCommands";
import type { FindingState } from "../../domain/finding";
import { codePointLength, hasVisibleCharacter } from "../../domain/findingDraft";
import { wallTimeToIso } from "../../domain/time";
export type Grade = "GENERAL" | "MAJOR";
export const REASON_MAX = 4000;
export const REQUIREMENTS_MAX = 4000;

/** 派单表单的值；dueAt 是上海墙上时间 YYYY-MM-DDTHH:mm（datetime-local）。 */
export interface DispatchValues {
  assigneeMemberId: string;
  dueAt: string;
  requirements: string;
}
export const EMPTY_DISPATCH: DispatchValues = { assigneeMemberId: "", dueAt: "", requirements: "" };
export type DispatchErrors = Partial<Record<"assignee" | "dueAt" | "requirements", string>>;

export interface ActionDef {
  id: string;
  label: string;
  /** 需要填写的输入。 */
  reason?: "required" | "optional";
  grade?: boolean;
  supplement?: boolean;
  cycleText?: boolean;
  /** 派单：需要选责任人、填期限与要求，不用意见 / 等级。 */
  dispatch?: boolean;
  build(input: { reason: string; grade: "GENERAL" | "MAJOR"; dispatch: DispatchValues }): FindingCommand;
}

const dispatchAction: ActionDef = {
  id: "dispatch",
  label: "分派整改",
  dispatch: true,
  build: ({ dispatch: { assigneeMemberId, dueAt, requirements } }) => ({
    kind: "dispatch",
    assigneeMemberId,
    dueAt: wallTimeToIso(dueAt) ?? dueAt,
    requirements: requirements.trim(),
  }),
};

/** 契约已有、前端已接线的动作。按钮出现与否只是呈现，服务端对身份、职责、状态、版本、轮次最终校验。 */
export const ACTIONS: Partial<Record<FindingState, readonly ActionDef[]>> = {
  DISCOVERED: [{ id: "init", label: "启动确认流程", build: () => ({ kind: "initialize" }) }],
  SAFETY_DISPATCH_PENDING: [dispatchAction],
  // 验收退回后的新一轮同样处于 RECTIFYING 且尚无责任人，需要重新分派；前端不知道当前轮是否已有责任人，
  // 已有时服务端返回 409，候选查询同样 409，面板会提示。
  RECTIFYING: [{ ...dispatchAction, id: "redispatch", label: "重新分派" }],
  ACCEPTANCE_PENDING: [
    { id: "pass", label: "验收通过", reason: "optional", build: ({ reason }) => ({ kind: "verify", decision: "PASS", ...(reason ? { reason } : {}) }) },
    { id: "fail", label: "退回整改", reason: "required", build: ({ reason }) => ({ kind: "verify", decision: "FAIL", reason }) },
  ],
};

export const BLOCKED: Record<NonNullable<ReviewHistory["blockedReason"]>, string> = {
  WORKFLOW_NOT_INITIALIZED: "确认流程尚未启动。",
  UNSUPPORTED_REVIEW_PROTOCOL: "当前流程暂不支持此操作，请联系管理员。",
  NO_REVIEW_ACTION_AT_CURRENT_STATE: "当前节点没有确认或补充操作。",
  REVIEW_ROUTE_UNAVAILABLE: "确认职责配置缺失或冲突，请联系管理员。",
  NOT_CURRENT_REVIEW_PARTICIPANT: "当前操作应由指定确认人或原上报人完成。",
  PERSON_APPROVAL_REQUIRED: "本次确认需要先完成一人确认。",
  CURRENT_TASK_NOT_ASSIGNED: "当前任务尚未分配给你。",
  ACTION_NOT_AUTHORIZED: "你当前没有此操作权限。",
  CURRENT_TASK_UNAVAILABLE: "当前任务已变化，请刷新后查看。",
};

export function reviewActions(history: ReviewHistory | null): readonly ActionDef[] {
  if (!history || history.blockedReason) return [];
  const cycle = history.reviewProtocol === "CYCLE_V1" && history.processDefinitionKey === "findingRemediationV3";
  const legacy = history.reviewProtocol === "LEGACY" && ["findingRemediationV1", "findingRemediationV2"].includes(history.processDefinitionKey ?? "");
  if (!cycle && !legacy) return [];
  if (!Number.isSafeInteger(history.currentVersion) || history.currentVersion < 1 || !Number.isInteger(history.reviewCycle) || history.reviewCycle < 1) return [];
  const stage = history.reviewStage;
  return history.availableReviewActions.flatMap((decision): ActionDef[] => {
    if (decision === "SUPPLEMENT") return cycle && stage === null ? [{ id: decision, label: "补充说明", reason: "required", supplement: true, cycleText: true, build: ({ reason }) => ({ kind: "supplement", reviewCycle: history.reviewCycle, statement: reason }) }] : [];
    if (!["APPROVE", "RETURN_FOR_INFORMATION", "NOT_ESTABLISHED"].includes(decision)) return [];
    if ((stage !== "PERSON" && stage !== "SAFETY") || (!cycle && decision === "RETURN_FOR_INFORMATION") || (!cycle && decision === "NOT_ESTABLISHED" && history.processDefinitionKey !== "findingRemediationV2")) return [];
    const label = decision === "APPROVE" ? (stage === "PERSON" ? "确认发现" : "安全确认") : decision === "RETURN_FOR_INFORMATION" ? "退回补充" : "判定未成立";
    return [{ id: decision, label, reason: "required", grade: stage === "SAFETY" && decision === "APPROVE", cycleText: cycle, build: ({ reason, grade }) => {
      if (cycle) {
        if (stage === "SAFETY" && decision === "APPROVE") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason, grade };
        if (stage === "PERSON") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason };
        if (decision !== "APPROVE") return { kind: "reviewDecision", reviewCycle: history.reviewCycle, stage, decision, reason };
        throw new Error("Invalid review decision");
      }
      if (decision === "NOT_ESTABLISHED") return { kind: "closeNotEstablished", stage, reason };
      return stage === "PERSON" ? { kind: "confirmPerson", reason } : { kind: "confirmSafety", reason, grade };
    } }];
  });
}

export const FAILURE_MESSAGE: Record<FindingCommandFailureKind, string> = {
  validation: "提交内容未通过服务端校验，请检查后重试。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你无权执行此操作，或这条记录对你不可见。",
  versionConflict: "这条记录刚被他人更新，已为你重新读取最新状态。请确认后再操作。",
  idempotencyConflict: "这次提交与之前的一次提交内容不一致，已被拒绝。请重新操作。",
  stateConflict: "当前流程状态不允许此操作（可能已被他人处理），已为你重新读取最新状态。",
  workflow: "流程规则不满足（例如操作人职责冲突，或没有可用的下一步处理人）。请联系管理员处理。",
  unavailable: "服务暂时不可用，操作尚未确认成功。可以直接重试，重试不会重复执行。",
};

/** 失败后需要重新读取详情的类别：版本或状态已变，当前页面上的内容过期。 */
export const REFRESH_ON: ReadonlySet<FindingCommandFailureKind> = new Set(["versionConflict", "stateConflict"]);


export const UNAVAILABLE: Partial<Record<FindingState, readonly { label: string; reason: string }[]>> = { RECTIFYING: [{ label: "提交整改证据", reason: "提交整改需要先上传证据集，契约还没有证据上传接口。" }] };
export interface CommandInputErrors {
  reason?: string;
  grade?: string;
}

/** 本地校验；服务端仍会重新校验。意见只按码点计长、按可见字符判空。 */
export function validateCommandInput(action: ActionDef, reason: string, grade: "" | Grade): CommandInputErrors {
  const errors: CommandInputErrors = {};
  if (action.reason) {
    if ((action.cycleText ? reason.length : codePointLength(reason)) > REASON_MAX) errors.reason = `不能超过 ${REASON_MAX} 个字`;
    else if (action.cycleText && /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(reason)) errors.reason = "请检查文字内容";
    else if (action.reason === "required" && !hasVisibleCharacter(reason)) errors.reason = "请填写意见";
  }
  if (action.grade && !grade) errors.grade = "请选择隐患等级";
  return errors;
}

/** 派单本地校验；服务端仍会重新校验。到期时间只检查格式，是否合理由服务端判断。 */
export function validateDispatchInput(values: DispatchValues): DispatchErrors {
  const errors: DispatchErrors = {};
  if (!values.assigneeMemberId) errors.assignee = "请选择整改责任人";
  if (!values.dueAt) errors.dueAt = "请填写整改期限";
  else if (wallTimeToIso(values.dueAt) === null) errors.dueAt = "整改期限格式不正确";
  if (codePointLength(values.requirements) > REQUIREMENTS_MAX) errors.requirements = `不能超过 ${REQUIREMENTS_MAX} 个字`;
  else if (!hasVisibleCharacter(values.requirements)) errors.requirements = "请填写整改要求";
  return errors;
}
