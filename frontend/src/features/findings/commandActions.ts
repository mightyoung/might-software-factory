import type { FindingCommand, FindingCommandFailureKind } from "../../adapters/findingCommands";
import type { FindingState } from "../../domain/finding";
import { codePointLength, hasVisibleCharacter } from "../../domain/findingDraft";
import { wallTimeToIso } from "../../domain/time";

export const REASON_MAX = 4000;
export const REQUIREMENTS_MAX = 4000;

export type Grade = "GENERAL" | "MAJOR";
export type FieldKey = "reason" | "grade" | "assignee" | "dueAt" | "requirements";

/** 一个命令表单里用户填写的全部值；不同动作只用其中一部分。 */
export interface CommandValues {
  reason: string;
  grade: "" | Grade;
  assigneeMemberId: string;
  /** 上海墙上时间 YYYY-MM-DDTHH:mm（datetime-local）。 */
  dueAt: string;
  requirements: string;
}

export const EMPTY_VALUES: CommandValues = { reason: "", grade: "", assigneeMemberId: "", dueAt: "", requirements: "" };

export type CommandErrors = Partial<Record<FieldKey, string>>;

export interface FieldSpec {
  key: FieldKey;
  required: boolean;
}

export interface ActionDef {
  id: string;
  label: string;
  fields: readonly FieldSpec[];
  /** 通过本地校验后构造命令；这里的值已经 trim / 换算。 */
  build(values: CommandValues): FindingCommand;
}

const reason = (required: boolean): FieldSpec => ({ key: "reason", required });

const dispatch: ActionDef = {
  id: "dispatch",
  label: "分派整改",
  fields: [
    { key: "assignee", required: true },
    { key: "dueAt", required: true },
    { key: "requirements", required: true },
  ],
  build: ({ assigneeMemberId, dueAt, requirements }) => ({
    kind: "dispatch",
    assigneeMemberId,
    dueAt: wallTimeToIso(dueAt) ?? dueAt,
    requirements: requirements.trim(),
  }),
};

const notEstablished = (stage: "PERSON" | "SAFETY"): ActionDef => ({
  id: `not-established-${stage.toLowerCase()}`,
  label: "不成立",
  fields: [reason(true)],
  build: ({ reason: text }) => ({ kind: "closeNotEstablished", stage, reason: text.trim() }),
});

/** 契约已有、前端已接线的动作。按钮出现与否只是呈现，服务端对身份、职责、状态、版本、轮次最终校验。 */
export const ACTIONS: Partial<Record<FindingState, readonly ActionDef[]>> = {
  DISCOVERED: [{ id: "init", label: "启动确认流程", fields: [], build: () => ({ kind: "initialize" }) }],
  PERSON_CONFIRM_PENDING: [
    { id: "person", label: "确认发现", fields: [reason(true)], build: ({ reason: text }) => ({ kind: "confirmPerson", reason: text.trim() }) },
    notEstablished("PERSON"),
  ],
  SAFETY_CONFIRM_PENDING: [
    {
      id: "safety",
      label: "安全确认",
      fields: [reason(true), { key: "grade", required: true }],
      build: ({ reason: text, grade }) => ({ kind: "confirmSafety", reason: text.trim(), grade: grade || "GENERAL" }),
    },
    notEstablished("SAFETY"),
  ],
  SAFETY_DISPATCH_PENDING: [dispatch],
  // 验收退回后的新一轮同样处于 RECTIFYING 且尚无责任人，需要重新分派；前端不知道当前轮是否已有责任人，
  // 已有时服务端返回 409，候选查询同样 409，面板会提示。
  RECTIFYING: [{ ...dispatch, id: "redispatch", label: "重新分派" }],
  ACCEPTANCE_PENDING: [
    { id: "pass", label: "验收通过", fields: [reason(false)], build: ({ reason: text }) => ({ kind: "verify", decision: "PASS", ...(text.trim() ? { reason: text.trim() } : {}) }) },
    { id: "fail", label: "退回整改", fields: [reason(true)], build: ({ reason: text }) => ({ kind: "verify", decision: "FAIL", reason: text.trim() }) },
  ],
};

/** 契约暂缺或后端未实现的动作：保持禁用并写明原因，不假装可用。 */
export const UNAVAILABLE: Partial<Record<FindingState, readonly { label: string; reason: string }[]>> = {
  PERSON_CONFIRM_PENDING: [{ label: "退回补充", reason: "后端尚未实现“退回补充”。" }],
  SAFETY_CONFIRM_PENDING: [{ label: "退回第一确认", reason: "后端尚未实现“退回第一确认”。" }],
  RECTIFYING: [{ label: "提交整改证据", reason: "提交整改需要先上传证据集，契约还没有证据上传接口。" }],
};

export const FAILURE_MESSAGE: Record<FindingCommandFailureKind, string> = {
  validation: "提交内容未通过服务端校验，请检查后重试。",
  unauthenticated: "登录已失效，请重新登录。",
  forbidden: "你无权执行此操作，或这条记录对你不可见。",
  versionConflict: "这条记录刚被他人更新，已为你重新读取最新状态。请确认后再操作。",
  idempotencyConflict: "这次提交与之前的一次提交内容不一致，已被拒绝。请重新操作。",
  stateConflict: "当前流程状态不允许此操作（可能已被他人处理，或当前轮已有责任人），已为你重新读取最新状态。",
  workflow: "流程规则不满足（例如操作人职责冲突、所选成员不再有资格，或没有可用的下一步处理人）。请联系管理员处理。",
  unavailable: "服务暂时不可用，操作尚未确认成功。可以直接重试，重试不会重复执行。",
};

/** 失败后需要重新读取详情的类别：版本或状态已变，当前页面上的内容过期。 */
export const REFRESH_ON: ReadonlySet<FindingCommandFailureKind> = new Set(["versionConflict", "stateConflict"]);

/** 本地校验；服务端仍会重新校验。意见按码点计长、按可见字符判空；到期时间只检查格式，是否合理由服务端判断。 */
export function validateCommandInput(action: ActionDef, values: CommandValues): CommandErrors {
  const errors: CommandErrors = {};
  for (const { key, required } of action.fields) {
    if (key === "reason") {
      if (codePointLength(values.reason) > REASON_MAX) errors.reason = `不能超过 ${REASON_MAX} 个字`;
      else if (required && !hasVisibleCharacter(values.reason)) errors.reason = "请填写意见";
    } else if (key === "grade") {
      if (required && !values.grade) errors.grade = "请选择隐患等级";
    } else if (key === "assignee") {
      if (required && !values.assigneeMemberId) errors.assignee = "请选择整改责任人";
    } else if (key === "dueAt") {
      if (!values.dueAt) errors.dueAt = "请填写整改期限";
      else if (wallTimeToIso(values.dueAt) === null) errors.dueAt = "整改期限格式不正确";
    } else if (key === "requirements") {
      if (codePointLength(values.requirements) > REQUIREMENTS_MAX) errors.requirements = `不能超过 ${REQUIREMENTS_MAX} 个字`;
      else if (required && !hasVisibleCharacter(values.requirements)) errors.requirements = "请填写整改要求";
    }
  }
  return errors;
}
