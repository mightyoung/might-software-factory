import { describe, expect, it, vi } from "vitest";
import { ApiError, type HttpClient } from "../../api/httpClient";
import { createHttpEntityLedger } from "./httpEntityLedger";
import { DEF, ID, OTHER_ID, RECORD, data } from "./testFixture";
function http() {
  const response = { data: data(), meta: { traceId: "trace" } };
  const get = vi.fn(async () => response as unknown), post = vi.fn(async () => response as unknown);
  return { client: { get, post, put: post } as HttpClient, get, post };
}
const command = { commandId: ID, id: ID, version: 1, definitionVersion: "1", eventId: OTHER_ID, occurredAt: "2026-11-15T00:00:00Z", replay: false };
describe("standard ledger HTTP adapter", () => {
  it("parses the dynamic definition and sends the complete query contract", async () => {
    const h = http(), adapter = createHttpEntityLedger(h.client, "inventory", "widget");
    expect((await adapter.definition("READ")).definition.appKey).toBe("inventory");
    expect(h.get).toHaveBeenCalledWith("/api/v1/apps/inventory/entities/widget/definition?use=READ", { signal: undefined });
    h.post.mockResolvedValue({ data: { records: [RECORD], page: 1, size: 20, total: 1, hasMore: false }, meta: { traceId: "t" } });
    expect((await adapter.query(DEF, { page: { number: 1 }, size: 20 })).records[0]).toEqual(RECORD);
    expect(h.post).toHaveBeenCalledWith("/api/v1/apps/inventory/entities/widget/query", { body: { definitionVersion: "1", query: { page: { number: 1 }, size: 20 } }, signal: undefined });
  });
  it("writes command metadata only with the caller's stable key and expected record version", async () => {
    const h = http(), adapter = createHttpEntityLedger(h.client, "inventory", "widget"); h.post.mockResolvedValue({ data: command, meta: { traceId: "t" } });
    expect(await adapter.create(DEF, { code: "W" }, ID)).toEqual(command);
    expect(await adapter.update(DEF, RECORD, { code: "updated" }, ID)).toEqual(command);
    expect(h.post.mock.calls[1]).toEqual([`/api/v1/apps/inventory/entities/widget/records/${ID}/update`, { body: { definitionVersion: "1", expectedVersion: 1, values: { code: "updated" } }, headers: { "Idempotency-Key": ID } }]);
    expect(h.get).not.toHaveBeenCalled();
  });
  it("rejects unknown business keys, response versions and command payload values", async () => {
    const h = http(), adapter = createHttpEntityLedger(h.client, "inventory", "widget");
    h.get.mockResolvedValue({ data: { ...RECORD, values: { tenantId: ID } }, meta: { traceId: "t" } });
    await expect(adapter.record(DEF, ID)).rejects.toThrow();
    h.get.mockResolvedValue({ data: { ...RECORD, definitionVersion: "2" }, meta: { traceId: "t" } });
    await expect(adapter.record(DEF, ID)).rejects.toThrow();
    h.post.mockResolvedValue({ data: { ...command, values: { code: "should not leak" } }, meta: { traceId: "t" } });
    await expect(adapter.create(DEF, { code: "W" }, ID)).rejects.toThrow();
  });
  it.each([0, 401, 403, 409, 503])("preserves HTTP %s errors and never retries or falls back", async status => {
    const h = http(), adapter = createHttpEntityLedger(h.client, "inventory", "widget"); const error = new ApiError(status, "FAIL", "trace", false);
    h.post.mockRejectedValue(error); await expect(adapter.create(DEF, { code: "W" }, ID)).rejects.toBe(error); expect(h.post).toHaveBeenCalledTimes(1); expect(h.get).not.toHaveBeenCalled();
  });
  it("references only use their registered route and an empty context", async () => {
    const h = http(), adapter = createHttpEntityLedger(h.client, "inventory", "widget"); h.post.mockResolvedValue({ data: { options: [{ id: ID, label: "范围" }], hasMore: false }, meta: { traceId: "t" } });
    await adapter.reference(DEF, "scope", "CREATE", "east");
    expect(h.post).toHaveBeenCalledWith("/api/v1/apps/inventory/entities/widget/references/scope/query", { body: { definitionVersion: "1", use: "CREATE", search: "east", context: {}, size: 20 }, signal: undefined });
    await expect(adapter.reference(DEF, "../other", "CREATE", "")).rejects.toThrow();expect(h.post).toHaveBeenCalledTimes(1);
  });
});
