import { expect, it, vi } from "vitest";
import type { HttpClient } from "../../api/httpClient";
import { createHttpLoans } from "./model";
const id = "11111111-1111-4111-8111-111111111111";
const row = { id, version: 1, state: "PENDING_ISSUE", definitionVersion: "1", organizationId: id, custodyScopeId: id, purpose: "试制", toolIds: [id], createdAt: "2026-11-18T00:00:00Z", updatedAt: "2026-11-18T00:00:00Z" };
function http(data: unknown) {
  const get = vi.fn(async () => ({ data, meta: { traceId: "t" } })), post = vi.fn(async () => ({ data: row, meta: { traceId: "t" } }));
  return { get, post, adapter: createHttpLoans({ get, post, put: post } as HttpClient) };
}
it("preserves the exact command key and version for create, revise and cancellation", async () => {
  const { adapter, post } = http(null);
  const input = { definitionVersion: "1", organizationId: id, custodyScopeId: id, purpose: "试制", toolIds: [id] };
  expect(await adapter.command("create", undefined, input, id)).toEqual(row);
  await adapter.command("revise", id, { ...input, expectedVersion: 3 }, id);
  await adapter.command("cancel", id, { definitionVersion: "1", expectedVersion: 3 }, id);
  expect(post.mock.calls).toEqual([
    ["/api/v1/tooling/loan-applications", { body: input, headers: { "Idempotency-Key": id } }],
    [`/api/v1/tooling/loan-applications/${id}/revise`, { body: { ...input, expectedVersion: 3 }, headers: { "Idempotency-Key": id } }],
    [`/api/v1/tooling/loan-applications/${id}/cancel`, { body: { definitionVersion: "1", expectedVersion: 3 }, headers: { "Idempotency-Key": id } }],
  ]);
});
it("passes edit identity to candidate authorization and parses independent department choices", async () => {
  const value = { departments: [{ id, label: "申请部门" }], scopes: [{ id, label: "保管范围", organizationId: id }], tools: [{ id, label: "工装" }] };
  const { adapter, get } = http(value);expect(await adapter.candidates(id, undefined, id)).toEqual(value);
  expect(get).toHaveBeenCalledWith(`/api/v1/tooling/loan-applications/candidates?custodyScopeId=${id}&loanId=${id}`, { signal: undefined });
});
it("rejects malformed records and candidate identifiers before rendering them", async () => {
  const { adapter } = http({ records: [{ ...row, version: -1 }] });await expect(adapter.list()).rejects.toThrow();
  await expect(adapter.detail("bad-id")).rejects.toThrow();
  await expect(adapter.candidates("bad-id")).rejects.toThrow();
});
