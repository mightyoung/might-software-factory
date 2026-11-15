import type { components } from "../../api/generated/entity-ledger";

export type LedgerDefinition = components["schemas"]["Definition"];
export type DefinitionData = components["schemas"]["DefinitionData"];
export type LedgerUse = components["schemas"]["Use"];
export type LedgerRecord = components["schemas"]["RecordData"];
export type LedgerCommand = components["schemas"]["CommandData"];
export type LedgerQuery = components["schemas"]["Query"];
export type LedgerQueryData = components["schemas"]["QueryData"];
export type LedgerReferenceData = components["schemas"]["ReferenceData"];
export type LedgerValues = components["schemas"]["Values"];
export type LedgerInputField = components["schemas"]["InputField"];
export type LedgerDraft = Record<string, string>;

export interface EntityLedgerAdapter {
  definition(use: LedgerUse, signal?: AbortSignal): Promise<DefinitionData>;
  query(definition: LedgerDefinition, query: LedgerQuery, signal?: AbortSignal): Promise<LedgerQueryData>;
  record(definition: LedgerDefinition, id: string, signal?: AbortSignal): Promise<LedgerRecord>;
  reference(definition: LedgerDefinition, key: string, use: LedgerUse, search: string, signal?: AbortSignal): Promise<LedgerReferenceData>;
  create(definition: LedgerDefinition, values: LedgerValues, key: string): Promise<LedgerCommand>;
  update(definition: LedgerDefinition, record: LedgerRecord, values: LedgerValues, key: string): Promise<LedgerCommand>;
}
export type EntityLedgerFactory = (appKey: string, entityKey: string) => EntityLedgerAdapter;

export const IDENTIFIER = /^[a-z][a-z0-9_-]{0,63}$/;
export const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const PERMISSION = /^[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+$/;
export const FIELD_TYPES = new Set(["TEXT", "MULTILINE_TEXT", "UUID", "INTEGER", "DATE_TIME", "ENUM", "REFERENCE"]);
export const PROTECTED = new Set(["id", "tenantId", "actorId", "accountId", "memberId", "version", "createdAt", "updatedAt", "createdByActorId", "updatedByActorId"]);
export class LedgerProtocolError extends Error {
  constructor() { super("不支持或无效的台账定义，请刷新后核对。"); this.name = "LedgerProtocolError"; }
}
export function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
export function requireProtocol(condition: unknown): asserts condition { if (!condition) throw new LedgerProtocolError(); }
export function exactKeys(value: Record<string, unknown>, keys: string[]) {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
export function strings(value: unknown): value is string[] { return Array.isArray(value) && value.every(item => typeof item === "string"); }
export function safeInteger(value: unknown): value is number { return typeof value === "number" && Number.isSafeInteger(value); }
export function validUnicode(value: string) { return !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(value); }
export function validTimestamp(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match) return false;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number) as [number, number, number, number, number, number];
  const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const zone = match[7]!;
  if (zone !== "Z") { const hours = Number(zone.slice(1, 3)), minutes = Number(zone.slice(4)); if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) return false; }
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]! && hour <= 23 && minute <= 59 && second <= 59 && Number.isFinite(Date.parse(value));
}
export function valueLabel(labels: Record<string, string> | undefined, value: string) {
  return labels && Object.hasOwn(labels, value) ? labels[value]! : value;
}
export function permits(session: readonly string[], effective: readonly string[], permission: string) {
  return session.includes(permission) && effective.includes(permission);
}
