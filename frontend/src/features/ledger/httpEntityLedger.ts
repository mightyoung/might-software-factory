import { ApiError, type HttpClient } from "../../api/httpClient";
import type { components } from "../../api/generated/entity-ledger";
import { parseDefinition } from "./definition";
import { IDENTIFIER, object, exactKeys, requireProtocol as check, safeInteger, strings, UUID, validTimestamp, type EntityLedgerAdapter, type LedgerDefinition, type LedgerRecord } from "./model";

type Schemas = components["schemas"];
function record(value: unknown, definition: LedgerDefinition): LedgerRecord {
  check(object(value) && exactKeys(value, ["id", "version", "definitionVersion", "values", "effectiveActions"]) && typeof value.id === "string" && UUID.test(value.id) && safeInteger(value.version) && value.version >= 1 && value.definitionVersion === definition.definitionVersion && object(value.values) && strings(value.effectiveActions));
  check(Object.keys(value.values).every(key => Object.hasOwn(definition.fields, key)));
  const allowed = Object.values(definition.actions).map(action => action.permission);
  check(value.effectiveActions.every(permission => allowed.includes(permission)));
  for (const [key, v] of Object.entries(value.values)) {
    const field = definition.fields[key]!;
    check(v === null ? field.nullable : field.type === "INTEGER" ? safeInteger(v) : typeof v === "string");
    if (v !== null && ["UUID", "REFERENCE"].includes(field.type)) check(UUID.test(String(v)));
    if (v !== null && field.type === "DATE_TIME") check(validTimestamp(v));
  }
  return value as unknown as LedgerRecord;
}
function command(value: unknown, definition: LedgerDefinition, expectedId?: string): Schemas["CommandData"] {
  check(object(value) && exactKeys(value, ["commandId", "id", "version", "definitionVersion", "eventId", "occurredAt", "replay"]) && UUID.test(String(value.commandId)) && UUID.test(String(value.id)) && UUID.test(String(value.eventId)) && safeInteger(value.version) && value.version >= 1 && typeof value.definitionVersion === "string" && validTimestamp(value.occurredAt) && typeof value.replay === "boolean");
  check(value.definitionVersion === definition.definitionVersion && (expectedId === undefined || value.id === expectedId));
  return value as Schemas["CommandData"];
}
function data(value: unknown): unknown { check(object(value) && object(value.meta) && typeof value.meta.traceId === "string" && Object.hasOwn(value, "data")); return value.data; }

export function createHttpEntityLedger(client: HttpClient, app: string, entity: string): EntityLedgerAdapter {
  check(IDENTIFIER.test(app) && IDENTIFIER.test(entity));
  const base = `/api/v1/apps/${encodeURIComponent(app)}/entities/${encodeURIComponent(entity)}`;
  return {
    async definition(use, signal) { return parseDefinition(data(await client.get(`${base}/definition?use=${use}`, { signal })), app, entity, use); },
    async query(definition, query, signal) {
      const value = data(await client.post(`${base}/query`, { body: { definitionVersion: definition.definitionVersion, query }, signal }));
      check(object(value) && exactKeys(value, ["records", "page", "size", "total", "hasMore"]) && Array.isArray(value.records) && safeInteger(value.page) && value.page >= 1 && typeof value.size === "number" && [20, 50, 100].includes(value.size) && safeInteger(value.total) && value.total >= 0 && typeof value.hasMore === "boolean");
      check(value.records.length <= value.size && value.page === (query.page?.number ?? 1) && value.size === query.size);
      check(new Set(value.records.map(v => object(v) ? v.id : null)).size === value.records.length);
      return { records: value.records.map(v => record(v, definition)), page: value.page, size: value.size as 20 | 50 | 100, total: value.total, hasMore: value.hasMore };
    },
    async record(definition, id, signal) { check(UUID.test(id)); return record(data(await client.get(`${base}/records/${encodeURIComponent(id)}`, { signal })), definition); },
    async reference(definition, key, use, search, signal) {
      check(Object.hasOwn(definition.references, key) && [...search].length <= 200);
      const value = data(await client.post(`${base}/references/${encodeURIComponent(key)}/query`, { body: { definitionVersion: definition.definitionVersion, use, search, context: {}, size: 20 }, signal }));
      check(object(value) && exactKeys(value, ["options", "hasMore"]) && Array.isArray(value.options) && value.options.length <= 50 && typeof value.hasMore === "boolean");
      for (const option of value.options) check(object(option) && exactKeys(option, ["id", "label"]) && UUID.test(String(option.id)) && typeof option.label === "string");
      check(new Set(value.options.map(option => option.id)).size === value.options.length);
      return value as unknown as Schemas["ReferenceData"];
    },
    async create(definition, values, key) { check(UUID.test(key)); return command(data(await client.post(`${base}/records`, { body: { definitionVersion: definition.definitionVersion, values }, headers: { "Idempotency-Key": key } })), definition); },
    async update(definition, current, values, key) { check(UUID.test(key)); return command(data(await client.post(`${base}/records/${encodeURIComponent(current.id)}/update`, { body: { definitionVersion: definition.definitionVersion, expectedVersion: current.version, values }, headers: { "Idempotency-Key": key } })), definition, current.id); },
  };
}

export function ledgerFailure(error: unknown): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : "台账暂时不可用，请稍后重试。";
  if (error.status === 401) return "会话已失效，输入已保留，请重新登录。";
  if (error.status === 403 || error.status === 404) return "当前没有执行权限，或记录不可见。输入已保留。";
  if (error.code === "DEFINITION_CONFLICT") return "字段定义已更新，输入已保留。请刷新定义并核对后重新提交。";
  if (error.code === "VERSION_CONFLICT") return "记录已被其他人修改，输入已保留。请读取最新记录并核对后重新提交。";
  if (error.code === "IDEMPOTENCY_CONFLICT") return "此次请求与原提交内容不一致，输入已保留，请核对原提交。";
  if (error.status === 409) return "记录或操作状态冲突，输入已保留，请刷新核对。";
  if (error.status === 0 || error.status >= 500) return "提交结果尚未确认，输入和请求标识已保留。请先核对结果，勿重复新建。";
  return error.fieldErrors.length ? error.fieldErrors.map(field => field.reason).join("；") : "输入未通过检查，请核对后提交。";
}
