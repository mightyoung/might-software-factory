import * as z from "zod/mini";
import { validTimestamp, validUnicode, UUID, type LedgerDefinition, type LedgerDraft, type LedgerUse, type LedgerValues } from "./model";

const scalarText = z.custom<string>((value) => typeof value === "string" && validUnicode(value));

export function initialDraft(definition: LedgerDefinition, use: LedgerUse, values: LedgerValues = {}): LedgerDraft {
  return Object.fromEntries(Object.entries(definition.inputFields).filter(([, field]) => field.uses.includes(use)).map(([key]) => [key, values[key] === null || values[key] === undefined ? "" : String(values[key])]));
}
export function formValues(definition: LedgerDefinition, use: LedgerUse, draft: LedgerDraft): { values: Record<string, string | number | null>; errors: Record<string, string> } {
  const values: Record<string, string | number | null> = {}, errors: Record<string, string> = {};
  for (const [key, field] of Object.entries(definition.inputFields)) {
    if (!field.uses.includes(use)) continue;
    const raw = draft[key] ?? "";
    if (raw === "") { values[key] = null; if (field.required) errors[key] = "请填写此项。"; continue; }
    if (!scalarText.safeParse(raw).success || (field.maxLength > 0 && [...raw].length > field.maxLength)) { errors[key] = "字符无效或超过长度限制。"; continue; }
    if (field.type === "INTEGER") {
      if (!/^-?\d+$/.test(raw) || !Number.isSafeInteger(Number(raw))) { errors[key] = "请输入安全范围内的整数。"; continue; }
      values[key] = Number(raw);
    } else if (["UUID", "REFERENCE"].includes(field.type)) {
      if (!UUID.test(raw)) { errors[key] = field.type === "REFERENCE" ? "请从当前授权候选中选择。" : "请输入规范 UUID。"; continue; }
      values[key] = raw;
    } else if (field.type === "DATE_TIME") {
      if (!validTimestamp(raw)) { errors[key] = "请输入带时区的有效时间，最多六位小数。"; continue; }
      values[key] = raw;
    } else {
      if (field.allowedValues.length && !field.allowedValues.includes(raw)) { errors[key] = "请选择有效选项。"; continue; }
      values[key] = raw;
    }
  }
  return { values, errors };
}
