import { describe, expect, it } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
import type { FindingDraft } from "../domain/findingDraft";
import { createHttpFindingSubmit } from "./httpFindingSubmit";

const draft: FindingDraft = { source: "INSPECTION", description: "防护罩松动", location: "B 工房", foundAt: "2026-10-30T09:00", urgency: "IMMEDIATE", interimMeasure: "" };
const ok = { data: { findingId: "f1", code: "YH-20261030-0001", state: "PERSON_CONFIRM_PENDING", roundNo: 1, version: 1 } };

function client(result: unknown) {
  const calls: { path: string; req: { body: Record<string, unknown>; headers: Record<string, string> } }[] = [];
  const c = { post: async (path: string, req: never) => { calls.push({ path, req }); if (result instanceof Error) throw result; return result; } } as unknown as HttpClient;
  return { c, calls };
}

describe("createHttpFindingSubmit", () => {
  it("sends only reporter-owned fields with the idempotency key", async () => {
    const { c, calls } = client(ok);
    const result = await createHttpFindingSubmit(c, "org-1").submit(draft, { idempotencyKey: "k1" });
    expect(calls[0]!.path).toBe("/api/v1/findings");
    expect(calls[0]!.req.headers).toEqual({ "Idempotency-Key": "k1" });
    expect(calls[0]!.req.body).toEqual({ organizationId: "org-1", description: "防护罩松动", discoveredAt: "2026-10-30T09:00:00+08:00", grade: "UNKNOWN", sourceCategory: "INSPECTION", urgency: "URGENT", location: "B 工房" });
    expect(result).toEqual({ id: "f1", code: "YH-20261030-0001", state: "PERSON_CONFIRM_PENDING" });
  });

  it("rejects the front-end-only urgency level without calling the server", async () => {
    const { c, calls } = client(ok);
    await expect(createHttpFindingSubmit(c, "o").submit({ ...draft, urgency: "URGENT" }, { idempotencyKey: "k" })).rejects.toMatchObject({ kind: "validation", fieldErrors: { urgency: expect.any(String) } });
    expect(calls).toHaveLength(0);
  });

  it("maps statuses and backend field paths", async () => {
    const cases: [number, string][] = [[401, "unauthenticated"], [403, "forbidden"], [409, "conflict"], [422, "workflow"], [503, "unavailable"]];
    for (const [status, kind] of cases) {
      const { c } = client(new ApiError(status, "X", null, false));
      await expect(createHttpFindingSubmit(c, "o").submit(draft, { idempotencyKey: "k" })).rejects.toMatchObject({ kind });
    }
    const { c } = client(new ApiError(400, "INVALID", "t", false, [{ field: "sourceCategory", reason: "NotNull" }, { field: "items[0].x", reason: "Size" }]));
    const error = await createHttpFindingSubmit(c, "o").submit(draft, { idempotencyKey: "k" }).catch((e) => e);
    expect(Object.keys(error.fieldErrors)).toEqual(["source"]);
  });
});
