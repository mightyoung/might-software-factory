import { describe, expect, it } from "vitest";
import { createEmptyQuery, matchesQuery, type LedgerQueryField } from "./ledger";
import type { FindingRecord } from "./finding";
import { findingLedgerSchema } from "../features/findings/schema";
import { syntheticFindings } from "../adapters/findingLedger";

describe("字段类型驱动的查询模型", () => {
  it("从字段配置生成文本、枚举和日期区间的空值", () => {
    const fields = findingLedgerSchema.queryFields;
    const empty = createEmptyQuery(fields);

    expect(fields.map((field) => field.kind)).toEqual(["text", "enum", "dateRange"]);
    expect(empty.keyword).toBe("");
    expect(empty.state).toBe("");
    expect(empty.reportedAt).toEqual({ from: "", to: "" });
  });

  it("按字段类型过滤文字、状态和含端点的日期区间", () => {
    const row = syntheticFindings[0];
    expect(matchesQuery(row, findingLedgerSchema.queryFields, { keyword: "隔离", state: "", reportedAt: { from: "2026-10-29", to: "2026-10-29" } })).toBe(true);
    expect(matchesQuery(row, findingLedgerSchema.queryFields, { keyword: "demo-hz-001", state: "PERSON_CONFIRM_PENDING", reportedAt: { from: "", to: "2026-10-29" } })).toBe(true);
    expect(matchesQuery(row, findingLedgerSchema.queryFields, { keyword: "隔离", state: "CLOSED", reportedAt: { from: "", to: "" } })).toBe(false);
    expect(matchesQuery(row, findingLedgerSchema.queryFields, { keyword: "隔离", state: "", reportedAt: { from: "2026-10-30", to: "" } })).toBe(false);
  });

  it("schema field selector stays typed to a record field", () => {
    const typedField: LedgerQueryField<FindingRecord> = {
      key: "recordId",
      label: "编号",
      kind: "text",
      read: (record) => record.code,
    };
    expect(matchesQuery(syntheticFindings[0], [typedField], { recordId: "DEMO-HZ" })).toBe(true);
  });

  it("数值比较与引用筛选使用类型化值和已选对象 ID", () => {
    type Sample = { severity: number; responsibleOrgId: string };
    const fields: LedgerQueryField<Sample>[] = [
      { key: "severity", label: "严重度", kind: "number", operator: "atLeast", read: (record) => record.severity },
      {
        key: "responsibleOrgId",
        label: "责任部门",
        kind: "reference",
        options: [{ id: "demo-org-a", label: "演示部门 A" }],
        read: (record) => record.responsibleOrgId,
      },
    ];
    const empty = createEmptyQuery(fields);
    expect(empty).toEqual({ severity: "", responsibleOrgId: "" });
    expect(matchesQuery({ severity: 5, responsibleOrgId: "demo-org-a" }, fields, { severity: "4", responsibleOrgId: "demo-org-a" })).toBe(true);
    expect(matchesQuery({ severity: 5, responsibleOrgId: "demo-org-b" }, fields, { severity: "4", responsibleOrgId: "demo-org-a" })).toBe(false);
    expect(matchesQuery({ severity: 5, responsibleOrgId: "demo-org-a" }, fields, { severity: "6", responsibleOrgId: "demo-org-a" })).toBe(false);
  });
});
