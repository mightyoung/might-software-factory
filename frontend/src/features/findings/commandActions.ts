import type { FindingCommand, FindingCommandFailureKind } from "../../adapters/findingCommands";
import type { FindingState } from "../../domain/finding";
import { codePointLength, hasVisibleCharacter } from "../../domain/findingDraft";

export const REASON_MAX = 4000;

export type Grade = "GENERAL" | "MAJOR";

export interface ActionDef {
  id: string;
  label: string;
  /** 需要填写的输入。 */
  reason?: "required" | "optional";
  grade?: boolean;
  build(input: { reason: string; grade: Grade }): FindingCommand;
}

/** 契约已有、前端已接线的动作。按钮出现与否只是呈现，服务端对身份、职责、状态、版本、轮次最终校验。 */
export const ACTIONS: Partial<Record<FindingState, readonly ActionDef[]>> = {
  DISCOVERED: [{ id: "init", label: "启动确认流程", build: () => ({ kind: "initialize" }) }],
  PERSON_CONFIRM_PENDING: [
    { id: "person", label: "确认发现", reason: "required", build: ({ reason }) => ({ kind: "confirmPerson", reason }) },
  ],
  SAFETY_CONFIRM_PENDING: [
    {
      id: "safety",
      label: "安全确认",
      reason: "required",
      grade: true,
      build: ({ reason, grade }) => ({ kind: "confirmSafety", reason, grade }),
    },
  ],
  ACCEPTANCE_PENDING: [
    { id: "pass", label: "验收通过", reason: "optional", build: ({ reason }) => ({ kind: "verify", decision: "PASS", ...(reason ? { reason } : {}) }) },
    { id: "fail", label: "退回整改", reason: "required", build: ({ reason }) => ({ kind: "verify", decision: "FAIL", reason }) },
  ],
};

/** 契约暂缺或后端未实现的动作：保持禁用并写明原因，不假装可用。 */
export const UNAVAILABLE: Partial<Record<FindingState, readonly { label: string; reason: string }[]>> = {
  PERSON_CONFIRM_PENDING: [{ label: "退回补充", reason: "后端尚未实现“退回补充”。" }],
  SAFETY_CONFIRM_PENDING: [{ label: "退回第一确认", reason: "后端尚未实现“退回第一确认”。" }],
  SAFETY_DISPATCH_PENDING: [{ label: "分派整改", reason: "派单需要选择整改责任人，契约还没有成员查询接口。" }],
  RECTIFYING: [{ label: "提交整改证据", reason: "提交整改需要先上传证据集，契约还没有证据上传接口。" }],
};

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

export interface CommandInputErrors {
  reason?: string;
  grade?: string;
}

/** 本地校验；服务端仍会重新校验。意见只按码点计长、按可见字符判空。 */
export function validateCommandInput(action: ActionDef, reason: string, grade: "" | Grade): CommandInputErrors {
  const errors: CommandInputErrors = {};
  if (action.reason) {
    if (codePointLength(reason) > REASON_MAX) errors.reason = `不能超过 ${REASON_MAX} 个字`;
    else if (action.reason === "required" && !hasVisibleCharacter(reason)) errors.reason = "请填写意见";
  }
  if (action.grade && !grade) errors.grade = "请选择隐患等级";
  return errors;
}
