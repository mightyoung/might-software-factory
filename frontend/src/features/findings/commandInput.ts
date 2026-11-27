import * as z from "zod/mini";
import { codePointLength, hasVisibleCharacter } from "../../domain/findingDraft";
import { wallTimeToIso } from "../../domain/time";
import { REQUIREMENTS_MAX, REASON_MAX, type DispatchErrors, type DispatchValues, type ActionDef, type CommandInputErrors, type Grade } from "./commandActions";

export interface CommandInput { reason: string; grade: string }
const gradeSchema = z.custom<Grade>((value) => value === "GENERAL" || value === "MAJOR");

function reasonError(action: ActionDef, reason: string) {
  if (!action.reason) return;
  if ((action.cycleText ? reason.length : codePointLength(reason)) > REASON_MAX) return `不能超过 ${REASON_MAX} 个字`;
  if (action.cycleText && /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(reason)) return "请检查文字内容";
  if (action.reason === "required" && !hasVisibleCharacter(reason)) return "请填写意见";
}

export function parseCommandInput(action: ActionDef, input: CommandInput):
  { success: true; input: { reason: string; grade: Grade } } | { success: false; errors: CommandInputErrors } {
  const reasonCheck = z.string().check(z.superRefine((reason, ctx) => {
    const message = reasonError(action, reason);
    if (message) ctx.addIssue({ code: "custom", message });
  })).safeParse(input.reason);
  const gradeCheck = gradeSchema.safeParse(input.grade);
  const errors: CommandInputErrors = {};
  if (!reasonCheck.success) errors.reason = reasonCheck.error.issues[0].message;
  if (action.grade && !gradeCheck.success) errors.grade = "请选择隐患等级";
  if (errors.reason || errors.grade) return { success: false, errors };
  return { success: true, input: {
    reason: action.cycleText ? input.reason : input.reason.trim(),
    grade: gradeCheck.success ? gradeCheck.data : "GENERAL",
  } };
}


const dispatchDeadline = z.custom<string>((value) => typeof value === "string" && wallTimeToIso(value) !== null);
const dispatchRequirements = z.custom<string>((value) =>
  typeof value === "string" && codePointLength(value) <= REQUIREMENTS_MAX && hasVisibleCharacter(value),
);

/** The server remains authoritative for assignment eligibility and deadline policy. */
export function validateDispatchInput({ assigneeMemberId, dueAt, requirements }: DispatchValues): DispatchErrors {
  const errors: DispatchErrors = {};
  if (!assigneeMemberId) errors.assignee = "请选择整改责任人";
  if (!dispatchDeadline.safeParse(dueAt).success) errors.dueAt = dueAt ? "整改期限格式不正确" : "请填写整改期限";
  if (!dispatchRequirements.safeParse(requirements).success) errors.requirements = codePointLength(requirements) > REQUIREMENTS_MAX
    ? `不能超过 ${REQUIREMENTS_MAX} 个字`
    : "请填写整改要求";
  return errors;
}
