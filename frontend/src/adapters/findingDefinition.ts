import type { components } from "../api/generated/entity-definition";
import type { HttpClient } from "../api/httpClient";
import type { FindingRecord } from "../domain/finding";
import type { LedgerQueryField, LedgerSchema } from "../domain/ledger";
import { formatDateTime } from "../domain/time";
import { parseWallTime } from "../domain/time";
import { codePointLength, hasVisibleCharacter, normalizeDraft, type FindingDraft, type FindingDraftErrors, type FindingDraftField } from "../domain/findingDraft";
import { ENTITY_QUERY_OPERATORS, type RegisteredEntityV1 } from "./entityQueryContract";

export type FindingDefinition = components["schemas"]["EffectiveDefinition"];
export type DefinitionField = FindingDefinition["fields"][string];
export type DefinitionUse = FindingDefinition["use"];
type DefinitionResponse = components["schemas"]["DefinitionResponse"];

export class FindingDefinitionError extends Error {
  constructor() { super("Unsupported finding definition"); this.name = "FindingDefinitionError"; }
}

const TYPES = new Set(["TEXT", "NUMBER", "MONEY", "DATE", "DATETIME", "BOOLEAN", "ENUM", "UUID", "USER", "ORGANIZATION", "REFERENCE", "IMAGE", "ATTACHMENT"]);
const KINDS = new Set(["TEXT", "MULTILINE_TEXT", "DATE_TIME", "BOOLEAN", "ENUM", "REFERENCE", "STATUS", "NUMBER"]);
const OPERATORS = new Set<string>(ENTITY_QUERY_OPERATORS);
const CHECKSUM = /^[0-9a-f]{64}$/;
const INPUTS = ["organizationId", "description", "discoveredAt", "sourceEventId", "riskId", "grade", "sourceCategory", "urgency", "location", "interimMeasure", "relatedFindingId"];
export const FINDING_CREATE_CONTROLS = ["organizationId", "description", "discoveredAt", "sourceCategory", "urgency", "location", "interimMeasure"] as const;
export const REPORT_ORGANIZATION_QUERY = "/api/v1/entities/finding/references/report-organization/query";
export const CREATE_DRAFT_FIELDS: Readonly<Record<string, FindingDraftField>> = {
  description: "description", discoveredAt: "foundAt", sourceCategory: "source", urgency: "urgency", location: "location", interimMeasure: "interimMeasure",
};

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function requireDefinition(value: unknown): asserts value {
  if (!value) throw new FindingDefinitionError();
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === "string");
}
function sameKeys(actual: readonly string[], expected: readonly string[]): boolean {
  return actual.length === expected.length && expected.every((key) => actual.includes(key));
}
function exactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return sameKeys(Object.keys(value), keys);
}

/** HTTP types are not runtime validation. Unknown definitions fail without a synthetic fallback. */
export function parseFindingDefinition(value: unknown, use: DefinitionUse): FindingDefinition {
  requireDefinition(object(value));
  const data = value as Record<string, unknown>;
  requireDefinition(exactKeys(data, ["appKey", "entityKey", "definitionVersion", "querySchemaVersion", "use", "title", "publicationChecksum", "fields", "layout", "actions", "references"])
    && data.entityKey === "finding" && data.definitionVersion === "1" && data.querySchemaVersion === "5"
    && data.use === use && typeof data.appKey === "string" && typeof data.title === "string"
    && typeof data.publicationChecksum === "string" && CHECKSUM.test(data.publicationChecksum)
    && object(data.fields) && object(data.layout) && object(data.actions) && object(data.references));
  const fields = data.fields as Record<string, unknown>;
  requireDefinition(Object.keys(fields).length > 0 && Object.keys(fields).length <= 64);
  for (const field of Object.values(fields)) {
    requireDefinition(object(field) && exactKeys(field, ["query", "presentation"]) && object(field.query) && object(field.presentation));
    const query = field.query as Record<string, unknown>;
    const presentation = field.presentation as Record<string, unknown>;
    requireDefinition(exactKeys(query, ["type", "nullable", "selectable", "sortable", "operators", "maxLength", "enumValues"])
      && exactKeys(presentation, ["label", "uiKind", "history", "referenceKey", "valueLabels", "edit"])
      && TYPES.has(String(query.type)) && typeof query.nullable === "boolean"
      && typeof query.selectable === "boolean" && typeof query.sortable === "boolean"
      && strings(query.operators) && query.operators.every((operator) => OPERATORS.has(operator))
      && Number.isInteger(query.maxLength) && Number(query.maxLength) >= 0 && Number(query.maxLength) <= 100000 && strings(query.enumValues)
      && typeof presentation.label === "string" && presentation.label.length > 0
      && KINDS.has(String(presentation.uiKind)) && ["CURRENT", "SNAPSHOT"].includes(String(presentation.history))
      && (presentation.referenceKey === null || typeof presentation.referenceKey === "string")
      && object(presentation.valueLabels) && Object.values(presentation.valueLabels).every((label) => typeof label === "string"));
    if (presentation.edit !== null) {
      requireDefinition(object(presentation.edit));
      const edit = presentation.edit as Record<string, unknown>;
      requireDefinition(exactKeys(edit, ["required", "maxLength", "lengthUnit", "allowedValues"])
        && typeof edit.required === "boolean" && Number.isInteger(edit.maxLength)
        && Number(edit.maxLength) >= 0 && ["UTF16_CODE_UNITS", "UNICODE_CODE_POINTS"].includes(String(edit.lengthUnit))
        && strings(edit.allowedValues));
    }
  }
  const layout = data.layout as Record<string, unknown>;
  requireDefinition(exactKeys(layout, ["list", "detail", "create", "query"]));
  for (const key of ["list", "detail", "create", "query"]) {
    const keys = layout[key];
    requireDefinition(strings(keys) && keys.length <= 64 && new Set(keys).size === keys.length
      && keys.every((field) => Object.hasOwn(fields, field)));
  }
  const definition = data as unknown as FindingDefinition;
  if (use === "READ") {
    requireDefinition(definition.layout.create.length === 0 && Object.keys(definition.actions).length === 0
      && Object.keys(definition.references).length === 0 && Object.values(definition.fields).every((field) => field.presentation.edit === null));
  } else {
    requireDefinition(sameKeys(Object.keys(fields), INPUTS) && sameKeys(definition.layout.create, FINDING_CREATE_CONTROLS));
    const action = definition.actions["finding.create"];
    const reference = definition.references["report-organization"];
    requireDefinition(Object.keys(definition.actions).length === 1 && action?.binding.method === "POST"
      && action.binding.path === "/api/v1/findings" && action.binding.permission === "finding.create"
      && action.position === "CREATE" && action.idempotencyKeyRequired === true && action.expectedVersionRequired === false
      && sameKeys(action.inputFields, INPUTS)
      && Object.keys(definition.references).length === 1 && reference?.entityKey === "organization"
      && reference.binding.resourceKey === "report-organization" && reference.binding.method === "POST"
      && reference.binding.permission === "finding.create" && reference.binding.path === REPORT_ORGANIZATION_QUERY);
    requireDefinition(Object.values(definition.fields).every((field) => field.presentation.edit !== null));
    requireDefinition(sameKeys(definition.fields.grade!.presentation.edit!.allowedValues, ["UNKNOWN"]));
  }
  return definition;
}

/** Every activation/read asks the server again; no permanent or adapter-lifetime authority cache. */
export async function loadFindingDefinition(client: HttpClient, use: DefinitionUse, signal?: AbortSignal): Promise<FindingDefinition> {
  const response = await client.get<DefinitionResponse>(`/api/v1/entities/finding/definition?use=${use}`, { signal });
  signal?.throwIfAborted();
  requireDefinition(response?.meta?.schemaVersion === "1");
  return parseFindingDefinition(response.data, use);
}

export function registeredFindingSchema(definition: FindingDefinition): RegisteredEntityV1 {
  requireDefinition(definition.use === "READ" && definition.fields.id?.query.selectable);
  const fields = Object.fromEntries(Object.entries(definition.fields).map(([key, field]) => [key, field.query]));
  return { key: "finding", schemaVersion: "5", idField: "id", defaultFields: Object.keys(fields).filter((key) => fields[key]!.selectable), fields };
}

export function publishedFieldValue(record: FindingRecord, key: string): string | number | boolean | null {
  return record.registeredValues?.[key] ?? null;
}

export function displayPublishedValue(field: DefinitionField, value: string | number | boolean | null): string {
  if (value === null) return "—";
  if (field.query.type === "DATETIME") return typeof value === "string" ? formatDateTime(value) : "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  return field.presentation.valueLabels[String(value)] ?? String(value);
}

export function findingSchemaFromDefinition(definition: FindingDefinition): LedgerSchema<FindingRecord> {
  requireDefinition(definition.use === "READ");
  const queryFields: LedgerQueryField<FindingRecord>[] = definition.layout.query.map((key) => {
    const field = definition.fields[key]!;
    const common = { key: key === "code" ? "keyword" : key, label: field.presentation.label };
    if (field.query.type === "TEXT" && field.query.operators.includes("CONTAINS"))
      return { ...common, kind: "text", read: (record) => String(publishedFieldValue(record, key) ?? "") };
    if (field.query.type === "ENUM" && field.query.operators.includes("EQ"))
      return { ...common, kind: "enum", options: field.query.enumValues.map((value) => ({ value, label: field.presentation.valueLabels[value] ?? value })), read: (record) => String(publishedFieldValue(record, key) ?? "") };
    if (["DATETIME", "DATE"].includes(field.query.type) && field.query.operators.includes("GTE") && field.query.operators.includes("LTE"))
      return { ...common, kind: field.query.type === "DATETIME" ? "dateTimeRange" : "dateRange", read: (record) => String(publishedFieldValue(record, key) ?? "") };
    if (["NUMBER", "MONEY"].includes(field.query.type) && field.query.operators.includes("EQ"))
      return { ...common, kind: "number", operator: "equals", read: (record) => { const value = publishedFieldValue(record, key); return typeof value === "number" ? value : null; } };
    throw new FindingDefinitionError();
  });
  return {
    entityKey: `${definition.appKey}.${definition.entityKey}`, title: definition.title,
    recordKey: (record) => record.id, recordLabel: (record) => record.code, queryFields,
    columns: definition.layout.list.map((key) => {
      const field = definition.fields[key]!;
      return { key, label: field.presentation.label, kind: field.presentation.uiKind === "STATUS" ? "status" : "text",
        read: (record) => displayPublishedValue(field, publishedFieldValue(record, key)),
        ...(field.presentation.uiKind === "STATUS" ? { tone: (record: FindingRecord) => String(publishedFieldValue(record, key) ?? "").toLowerCase().replaceAll("_", "-") } : {}) };
    }),
  };
}

export function queryBindings(definition: FindingDefinition): Readonly<Record<string, string>> {
  return Object.fromEntries(definition.layout.query.map((key) => [key === "code" ? "keyword" : key, key]));
}

export function publishedInputLength(value: string, field: DefinitionField): number {
  return field.presentation.edit?.lengthUnit === "UTF16_CODE_UNITS" ? value.length : codePointLength(value);
}

/** Live validation uses published input hints; synthetic product assumptions stay in the synthetic draft validator. */
export function validatePublishedFindingDraft(input: FindingDraft, definition: FindingDefinition, now: Date): FindingDraftErrors {
  const draft = normalizeDraft(input);
  const errors: FindingDraftErrors = {};
  for (const key of definition.layout.create) {
    const draftKey = CREATE_DRAFT_FIELDS[key];
    if (!draftKey) continue; // organization selection is handled by its dedicated control.
    const field = definition.fields[key]!;
    const edit = field.presentation.edit!;
    const value = draft[draftKey];
    if (edit.required && !hasVisibleCharacter(value)) errors[draftKey] = `请填写${field.presentation.label}`;
    else if (edit.maxLength > 0 && publishedInputLength(value, field) > edit.maxLength)
      errors[draftKey] = `${field.presentation.label}超过允许长度（${edit.maxLength}）`;
    if (edit.allowedValues.length > 0) {
      const wire = key === "urgency" ? (value === "IMMEDIATE" ? "URGENT" : value === "URGENT" ? null : value) : value;
      if (wire === null || !edit.allowedValues.includes(wire)) errors[draftKey] = `请选择允许的${field.presentation.label}`;
    }
  }
  const found = parseWallTime(draft.foundAt);
  if (!found) errors.foundAt = "发现时间格式不正确";
  else if (found.getTime() > now.getTime() + 5 * 60_000) errors.foundAt = "发现时间不能晚于当前时间";
  return errors;
}
