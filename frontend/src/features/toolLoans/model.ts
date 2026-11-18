import { ApiError, type HttpClient } from "../../api/httpClient";
import { object, UUID, requireProtocol, safeInteger } from "../ledger/model";

export interface LoanRecord {
  id: string;
  version: number;
  state: "PENDING_ISSUE" | "WITHDRAWN";
  definitionVersion: string;
  organizationId: string;
  custodyScopeId: string;
  purpose: string;
  toolIds: string[];
  tools?: { id: string; label: string }[];
  createdAt: string;
  updatedAt: string;
}
export interface LoanInput { definitionVersion: string; organizationId: string; custodyScopeId: string; purpose: string; toolIds: string[]; expectedVersion?: number }
export interface LoanScope { id: string; label: string; organizationId: string }
export interface LoanCandidates { departments: { id: string; label: string }[]; scopes: LoanScope[]; tools: { id: string; label: string }[] }
export interface LoanAdapter {
  list(signal?: AbortSignal): Promise<LoanRecord[]>;
  detail(id: string, signal?: AbortSignal): Promise<LoanRecord>;
  candidates(scope?: string, signal?: AbortSignal, loanId?: string): Promise<LoanCandidates>;
  command(action: "create" | "revise" | "cancel", id: string | undefined, input: LoanInput | { definitionVersion: string; expectedVersion: number }, key: string): Promise<LoanRecord>;
}
export type LoanFactory = () => LoanAdapter;
function unwrap(value: unknown): unknown {
  requireProtocol(object(value) && object(value.meta) && typeof value.meta.traceId === "string" && Object.hasOwn(value, "data"));
  return value.data;
}
function record(value: unknown): LoanRecord {
  requireProtocol(object(value) && UUID.test(String(value.id)) && safeInteger(value.version) && value.version > 0
    && ["PENDING_ISSUE", "WITHDRAWN"].includes(String(value.state)) && typeof value.definitionVersion === "string"
    && UUID.test(String(value.organizationId)) && UUID.test(String(value.custodyScopeId)) && typeof value.purpose === "string"
    && Array.isArray(value.toolIds) && value.toolIds.every(id => typeof id === "string" && UUID.test(id))
    && typeof value.createdAt === "string" && typeof value.updatedAt === "string");
  const toolIds = value.toolIds as string[];
  if (value.tools !== undefined) requireProtocol(Array.isArray(value.tools) && value.tools.length === toolIds.length && value.tools.every((tool, index) => object(tool) && tool.id === toolIds[index] && typeof tool.label === "string"));
  return value as unknown as LoanRecord;
}
export function createHttpLoans(client: HttpClient): LoanAdapter {
  const base = "/api/v1/tooling/loan-applications";
  return {
    async list(signal) {
      const value = unwrap(await client.get(base, { signal }));
      requireProtocol(object(value) && Array.isArray(value.records));
      return value.records.map(record);
    },
    async detail(id, signal) { requireProtocol(UUID.test(id));return record(unwrap(await client.get(`${base}/${id}`, { signal }))); },
    async candidates(scope, signal, loanId) {
      if (scope) requireProtocol(UUID.test(scope));
      if (loanId) requireProtocol(UUID.test(loanId));
      const query = new URLSearchParams();
      if (scope) query.set("custodyScopeId", scope);
      if (loanId) query.set("loanId", loanId);
      const value = unwrap(await client.get(`${base}/candidates${query.size ? `?${query}` : ""}`, { signal }));
      requireProtocol(object(value) && Array.isArray(value.departments) && Array.isArray(value.scopes) && Array.isArray(value.tools));
      requireProtocol(value.scopes.every(row => object(row) && UUID.test(String(row.id)) && UUID.test(String(row.organizationId)) && typeof row.label === "string"));
      requireProtocol(value.departments.every(row => object(row) && UUID.test(String(row.id)) && typeof row.label === "string"));
      requireProtocol(value.tools.every(row => object(row) && UUID.test(String(row.id)) && typeof row.label === "string"));
      return value as unknown as LoanCandidates;
    },
    async command(action, id, input, key) {
      requireProtocol(UUID.test(key) && (action === "create" || (id && UUID.test(id))));
      const path = action === "create" ? base : `${base}/${id}/${action}`;
      return record(unwrap(await client.post(path, { body: input, headers: { "Idempotency-Key": key } })));
    },
  };
}
export function unknownOutcome(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}
