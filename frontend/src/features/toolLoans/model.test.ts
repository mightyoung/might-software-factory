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
it("uses keeper-only queue/detail endpoints and posts one versioned issue command", async () => {
  const h = http({ records: [{ ...row, state: "ISSUED" }] });
  expect((await h.adapter.issueQueue())[0]!.state).toBe("ISSUED");
  expect(h.get).toHaveBeenCalledWith("/api/v1/tooling/loan-applications/issue-queue", { signal: undefined });
  h.get.mockResolvedValue({ data: { ...row, state: "ISSUED" }, meta: { traceId: "t" } });
  expect((await h.adapter.issueDetail(id)).state).toBe("ISSUED");
  expect(h.get).toHaveBeenCalledWith(`/api/v1/tooling/loan-applications/${id}/issue-detail`, { signal: undefined });
  await h.adapter.command("issue", id, { definitionVersion: "1", expectedVersion: 2 }, id);
  expect(h.post).toHaveBeenCalledWith(`/api/v1/tooling/loan-applications/${id}/issue`, { body: { definitionVersion: "1", expectedVersion: 2 }, headers: { "Idempotency-Key": id } });
});
it("parses issued and settled return progress and uses distinct return and receipt commands", async () => {
  const progress = { ...row, state: "SETTLED", returnItems: [{ toolId: id, label: "工装", state: "RECEIVED", condition: "DAMAGED", reason: "断裂" }] };
  const h = http({ records: [progress] });expect((await h.adapter.receiveQueue())[0]!.state).toBe("SETTLED");
  expect(h.get).toHaveBeenCalledWith("/api/v1/tooling/loan-applications/receive-queue", { signal: undefined });
  h.get.mockResolvedValue({ data: progress, meta: { traceId: "t" } });expect((await h.adapter.receiveDetail(id)).returnItems![0]!.condition).toBe("DAMAGED");
  expect(h.get).toHaveBeenCalledWith(`/api/v1/tooling/loan-applications/${id}/receive-detail`, { signal: undefined });
  await h.adapter.command("return-requests", id, { definitionVersion: "1", expectedVersion: 3, toolIds: [id] }, id);
  await h.adapter.command("receive", id, { definitionVersion: "1", expectedVersion: 4, items: [{ toolId: id, condition: "DAMAGED", reason: "断裂" }] }, id);
  expect(h.post.mock.calls[0]).toEqual([`/api/v1/tooling/loan-applications/${id}/return-requests`, { body: { definitionVersion: "1", expectedVersion: 3, toolIds: [id] }, headers: { "Idempotency-Key": id } }]);
  expect(h.post.mock.calls[1]).toEqual([`/api/v1/tooling/loan-applications/${id}/receive`, { body: { definitionVersion: "1", expectedVersion: 4, items: [{ toolId: id, condition: "DAMAGED", reason: "断裂" }] }, headers: { "Idempotency-Key": id } }]);
});
it("accepts empty pre-issue progress but rejects mismatched per-tool projections", async () => {
  const h = http({ records: [{ ...row, returnItems: [] }] });expect((await h.adapter.list())[0]!.returnItems).toEqual([]);
  h.get.mockResolvedValue({ data: { ...row, state: "ISSUED", returnItems: [{ toolId: "bad", label: "工装", state: "OUTSTANDING" }] }, meta: { traceId: "t" } });
  await expect(h.adapter.detail(id)).rejects.toThrow();
});
it("preserves a bound historical definition version for return and receipt", async () => {
  const historical = { ...row, state: "ISSUED", definitionVersion: "legacy-v0.9", returnItems: [{ toolId: id, label: "旧工装", state: "OUTSTANDING" }] };
  const h = http({ records: [historical] });expect((await h.adapter.list())[0]!.definitionVersion).toBe("legacy-v0.9");
  await h.adapter.command("return-requests", id, { definitionVersion: "legacy-v0.9", expectedVersion: 4, toolIds: [id] }, id);
  expect(h.post).toHaveBeenCalledWith(`/api/v1/tooling/loan-applications/${id}/return-requests`, expect.objectContaining({ body: { definitionVersion: "legacy-v0.9", expectedVersion: 4, toolIds: [id] } }));
  h.get.mockResolvedValue({ data: { ...historical, definitionVersion: " " }, meta: { traceId: "t" } });await expect(h.adapter.detail(id)).rejects.toThrow();
});

it("loads the authoritative published creation fields and rejects incoherent metadata", async () => {
  const value = { definitionVersion: "2", usageLocationRequired: true, usageLocationMaxLength: 200 };
  const h = http(value);
  expect(await h.adapter.definition()).toEqual(value);
  expect(h.get).toHaveBeenCalledWith("/api/v1/tooling/loan-applications/definition", { signal: undefined });
  h.get.mockResolvedValue({ data: { ...value, usageLocationRequired: false }, meta: { traceId: "t" } });
  await expect(h.adapter.definition()).rejects.toThrow();
});
it("requires the V2 location while accepting original V1 records", async () => {
  const h = http({ records: [{ ...row, definitionVersion: "2", usageLocation: "二车间" }] });
  expect((await h.adapter.list())[0]!.usageLocation).toBe("二车间");
  h.get.mockResolvedValue({ data: { records: [{ ...row, definitionVersion: "2" }] }, meta: { traceId: "t" } });
  await expect(h.adapter.list()).rejects.toThrow();
});
