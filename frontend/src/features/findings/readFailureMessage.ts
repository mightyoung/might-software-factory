import type { FindingLedgerReadFailureKind } from "../../adapters/findingLedger";

export type FindingReadTarget = "list" | "detail";

/** Maps provider-level semantic categories to neutral UI copy; no wire error code is implied. */
export function findingReadFailureMessage(
  kind: FindingLedgerReadFailureKind,
  target: FindingReadTarget,
): string {
  const recordName = target === "list" ? "隐患记录" : "隐患详情";
  if (kind === "unauthenticated") return `当前未完成身份验证，无法读取${recordName}。`;
  if (kind === "forbidden") return `当前身份无权读取${recordName}。`;
  return target === "list" ? "暂时无法加载隐患记录。" : "暂时无法读取隐患详情。";
}
