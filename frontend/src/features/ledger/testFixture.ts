import { vi } from "vitest";
import type { DefinitionData, EntityLedgerAdapter, LedgerDefinition, LedgerRecord } from "./model";
export const ID = "00000000-0000-4000-8000-000000000001";
export const OTHER_ID = "00000000-0000-4000-8000-000000000002";
export const DEF: LedgerDefinition = {
  appKey: "inventory", entityKey: "widget", definitionVersion: "1", title: "通用物品",
  fields: { code: { label: "编号", type: "TEXT", nullable: false, valueLabels: {} }, note: { label: "说明", type: "MULTILINE_TEXT", nullable: true, valueLabels: {} }, external: { label: "外部标识", type: "UUID", nullable: true, valueLabels: {} }, count: { label: "数量", type: "INTEGER", nullable: true, valueLabels: {} }, when: { label: "发生时间", type: "DATE_TIME", nullable: true, valueLabels: {} }, state: { label: "状态", type: "ENUM", nullable: true, valueLabels: { NORMAL: "正常" } }, scope: { label: "管理范围", type: "REFERENCE", nullable: false, valueLabels: {} } },
  inputFields: { code: { label: "编号", type: "TEXT", required: true, maxLength: 20, allowedValues: [], referenceKey: null, uses: ["CREATE", "EDIT"] }, note: { label: "说明", type: "MULTILINE_TEXT", required: false, maxLength: 4000, allowedValues: [], referenceKey: null, uses: ["CREATE", "EDIT"] }, external: { label: "外部标识", type: "UUID", required: false, maxLength: 36, allowedValues: [], referenceKey: null, uses: ["CREATE", "EDIT"] }, count: { label: "数量", type: "INTEGER", required: false, maxLength: 20, allowedValues: [], referenceKey: null, uses: ["CREATE", "EDIT"] }, when: { label: "发生时间", type: "DATE_TIME", required: false, maxLength: 40, allowedValues: [], referenceKey: null, uses: ["CREATE", "EDIT"] }, state: { label: "状态", type: "ENUM", required: false, maxLength: 30, allowedValues: ["NORMAL"], referenceKey: null, uses: ["CREATE", "EDIT"] }, scope: { label: "管理范围", type: "REFERENCE", required: true, maxLength: 36, allowedValues: [], referenceKey: "scope", uses: ["CREATE"] } },
  querySchema: { key: "widget", schemaVersion: "1", idField: "id", defaultFields: ["code", "state"], fields: {
    id: { type: "UUID", nullable: false, selectable: true, sortable: true, operators: ["EQ"], maxLength: 36, enumValues: [] },
    code: { type: "TEXT", nullable: false, selectable: true, sortable: true, operators: ["CONTAINS", "EQ"], maxLength: 20, enumValues: [] },
    count: { type: "NUMBER", nullable: true, selectable: true, sortable: true, operators: ["EQ", "GTE"], maxLength: 0, enumValues: [] },
    when: { type: "DATETIME", nullable: true, selectable: true, sortable: true, operators: ["GTE", "LTE"], maxLength: 0, enumValues: [] },
    state: { type: "ENUM", nullable: true, selectable: true, sortable: true, operators: ["EQ"], maxLength: 30, enumValues: ["NORMAL"] },
  } },
  layout: { list: ["code", "state"], detail: ["code", "note", "external", "count", "when", "state", "scope"], create: ["code", "note", "external", "count", "when", "state", "scope"], edit: ["code", "note", "external", "count", "when", "state"], query: ["code", "state", "count", "when"] },
  actions: { read: { label: "查看", permission: "widget.read" }, create: { label: "新增物品", permission: "widget.create" }, update: { label: "修改物品", permission: "widget.update" } }, references: { scope: { label: "管理范围" } },
};
export const RECORD: LedgerRecord = { id: ID, version: 1, definitionVersion: "1", values: { code: "W-01", note: "原说明", count: 2, state: "NORMAL", external: null, when: null, scope: OTHER_ID }, effectiveActions: ["widget.read", "widget.update"] };
export function data(use: DefinitionData["use"] = "READ", effectiveActions = ["widget.read", "widget.create", "widget.update"]): DefinitionData { return { definition: structuredClone(DEF), use, effectiveActions }; }
export function adapter(): EntityLedgerAdapter {
  return { definition: vi.fn(async use => data(use)), query: vi.fn(async () => ({ records: [RECORD], page: 1, size: 20 as const, total: 1, hasMore: false })), record: vi.fn(async () => structuredClone(RECORD)), reference: vi.fn(async () => ({ options: [{ id: OTHER_ID, label: "一号范围" }], hasMore: false })), create: vi.fn(async () => ({ commandId: ID, id: ID, version: 1, definitionVersion: "1", eventId: OTHER_ID, occurredAt: "2026-11-15T00:00:00Z", replay: false })), update: vi.fn(async () => ({ commandId: ID, id: ID, version: 2, definitionVersion: "1", eventId: OTHER_ID, occurredAt: "2026-11-15T00:00:00Z", replay: false })) };
}
