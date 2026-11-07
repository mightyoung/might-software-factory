import { describe, expect, it } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
import { findingUrgencyLabels } from "../domain/finding";
import { createHttpFindingSubmit } from "./httpFindingSubmit";
import { createHttpFindingLedger } from "./httpFindingLedger";

const field = (type: string, operators: string[], extra = {}) => ({ type, nullable: false, selectable: true, sortable: true, operators, maxLength: 0, enumValues: [], ...extra });
const schema = {
  key: "finding", schemaVersion: "3", idField: "id", defaultFields: ["id"],
  fields: {
    id: field("UUID", ["EQ", "IN"]),
    code: field("TEXT", ["EQ", "CONTAINS"], { maxLength: 64 }),
    state: field("ENUM", ["EQ", "IN"], { enumValues: ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "ACCEPTANCE_PENDING", "CLOSED"] }),
    createdAt: field("DATETIME", ["GTE", "LTE"]),
  },
};
const item = { id: "i1", code: "YH-1", description: "d", state: "RECTIFYING", grade: "UNKNOWN", roundNo: 2, createdAt: "2026-10-30T01:00:00Z", discoveredAt: "2026-10-30T00:00:00Z", location: null, urgency: "URGENT", backfill: null };
const q = { keyword: "", state: "", reportedAt: { from: "", to: "" } };

function fake(handlers: { post?: (body: unknown) => unknown; get?: (path: string) => unknown }) {
  const posts: unknown[] = [];
  let schemaCalls = 0;
  const client = {
    get: async (path: string) => {
      if (path.endsWith("/schema")) { schemaCalls++; return { data: schema }; }
      const r = handlers.get!(path); if (r instanceof Error) throw r; return r;
    },
    post: async (_p: string, req: { body: unknown }) => { posts.push(req.body); const r = handlers.post!(req.body); if (r instanceof Error) throw r; return r; },
  } as unknown as HttpClient;
  return { client, posts, schemaCalls: () => schemaCalls };
}
const listBody = { data: { items: [item], page: { number: 2, size: 20, total: 41, pageCount: 3 } }, meta: {} };

describe("createHttpFindingLedger", () => {
  it("maps query, appends the view filter, maps records, and fetches the schema once", async () => {
    const f = fake({ post: () => listBody });
    const ledger = createHttpFindingLedger(f.client);
    const result = await ledger.list({ query: { ...q, keyword: "YH" }, page: { index: 2, size: 20 }, view: "confirmation" });
    await ledger.list({ query: q, page: { index: 1, size: 20 }, view: "all" });
    expect(f.schemaCalls()).toBe(1);
    expect(f.posts[0]).toMatchObject({
      page: { number: 2 }, size: 20,
      filterAST: { operator: "AND", conditions: [
        { field: "code", operator: "CONTAINS", value: "YH" },
        { field: "state", operator: "IN", value: ["PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING"] },
      ] },
    });
    expect(f.posts[1]).not.toHaveProperty("filterAST");
    expect(result).toMatchObject({ page: { index: 2, size: 20 }, total: 41, pageCount: 3 });
    expect(result.items[0]).toMatchObject({ id: "i1", code: "YH-1", location: "—", reportedAt: "2026-10-30T01:00:00Z", foundAt: "2026-10-30T00:00:00Z", urgency: "IMMEDIATE", riskLabel: "待专业评估", round: 2 });
    expect(result.items[0]).not.toHaveProperty("backfill");
  });

  it.each([
    ["NORMAL", "NORMAL", "一般"],
    ["URGENT", "IMMEDIATE", "需立即处置"],
  ] as const)("maps backend %s urgency consistently in list and detail", async (backend, expected, label) => {
    const stored = { ...item, urgency: backend };
    const f = fake({
      post: () => ({ ...listBody, data: { ...listBody.data, items: [stored] } }),
      get: () => ({ data: stored }),
    });
    const ledger = createHttpFindingLedger(f.client);
    const listed = await ledger.list({ query: q, page: { index: 1, size: 20 }, view: "all" });
    const detail = await ledger.get(stored.id);
    for (const record of [listed.items[0], detail]) {
      expect(record?.urgency).toBe(expected);
      expect(findingUrgencyLabels[record!.urgency!]).toBe(label);
    }
  });

  it.each(["NORMAL", "IMMEDIATE"] as const)("preserves %s urgency through submit, list and detail", async (urgency) => {
    let persisted: typeof item | undefined;
    const client = {
      get: async (path: string) => ({ data: path.endsWith("/schema") ? schema : persisted }),
      post: async (path: string, request: { body: { urgency: string } }) => {
        if (path === "/api/v1/findings") {
          persisted = { ...item, urgency: request.body.urgency };
          return { data: { findingId: item.id, code: item.code, state: item.state } };
        }
        return { ...listBody, data: { ...listBody.data, items: [persisted] } };
      },
    } as unknown as HttpClient;
    const created = await createHttpFindingSubmit(client).submit({
      source: "INSPECTION", description: "防护罩松动", location: "B 工房",
      foundAt: "2026-10-30T09:00", urgency, interimMeasure: "",
    }, { idempotencyKey: "roundtrip", organizationId: "org-1" });
    expect(persisted?.urgency).toBe(urgency === "IMMEDIATE" ? "URGENT" : "NORMAL");
    const ledger = createHttpFindingLedger(client);
    const listed = await ledger.list({ query: q, page: { index: 1, size: 20 }, view: "all" });
    expect(listed.items[0].urgency).toBe(urgency);
    expect((await ledger.get(created.id))?.urgency).toBe(urgency);
  });

  it("a cancelled first call does not poison the shared schema request for the next call (StrictMode)", async () => {
    const f = fake({ post: () => listBody });
    const ledger = createHttpFindingLedger(f.client);
    const first = new AbortController();
    const req = { query: q, page: { index: 1, size: 20 }, view: "all" as const };
    const aborted = ledger.list(req, { signal: first.signal });
    first.abort();
    await expect(aborted).rejects.toMatchObject({ name: "AbortError" });
    await expect(ledger.list(req, { signal: new AbortController().signal })).resolves.toMatchObject({ total: 41 });
    expect(f.schemaCalls()).toBe(1);
  });

  it("refuses a report-date filter instead of silently ignoring it", async () => {
    const f = fake({ post: () => listBody });
    const call = createHttpFindingLedger(f.client).list({ query: { ...q, reportedAt: { from: "2026-10-01", to: "" } }, page: { index: 1, size: 20 }, view: "all" });
    await expect(call).rejects.toThrow(/binding/);
    expect(f.posts).toHaveLength(0);
  });

  it("maps 401/403/404 on list to access errors and other failures stay unknown", async () => {
    for (const [status, kind] of [[401, "unauthenticated"], [403, "forbidden"], [404, "forbidden"]] as const) {
      const f = fake({ post: () => new ApiError(status, "X", null, false) });
      await expect(createHttpFindingLedger(f.client).list({ query: q, page: { index: 1, size: 20 }, view: "all" })).rejects.toMatchObject({ name: "FindingLedgerAccessError", kind });
    }
    const f = fake({ post: () => new ApiError(503, "DEPENDENCY_UNAVAILABLE", "t", true) });
    await expect(createHttpFindingLedger(f.client).list({ query: q, page: { index: 1, size: 20 }, view: "all" })).rejects.toBeInstanceOf(ApiError);
  });

  it("returns null for an invisible detail and encodes the id", async () => {
    const paths: string[] = [];
    const f = fake({ get: (p) => { paths.push(p); return new ApiError(404, "RESOURCE_NOT_VISIBLE", null, false); } });
    expect(await createHttpFindingLedger(f.client).get("a/b")).toBeNull();
    expect(paths).toEqual(["/api/v1/entities/finding/a%2Fb"]);
  });

  it("fails the read when a required field is missing instead of inventing one", async () => {
    const { state: _state, ...withoutState } = item;
    const f = fake({ post: () => ({ data: { items: [withoutState], page: { number: 1, size: 20, total: 1, pageCount: 1 } }, meta: {} }) });
    const call = createHttpFindingLedger(f.client).list({ query: q, page: { index: 1, size: 20 }, view: "all" });
    await expect(call).rejects.toMatchObject({ name: "MalformedFindingResponseError" });
    await expect(call).rejects.not.toMatchObject({ name: "FindingLedgerAccessError" }); // 通用失败，不是未登录/无权
  });
});
