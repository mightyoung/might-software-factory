import { describe, expect, it } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
import { createHttpFindingCommands } from "./httpFindingCommands";
import type { FindingCommand } from "./findingCommands";

const ok = { data: { findingId: "f", code: "YH-1", state: "SAFETY_CONFIRM_PENDING", roundNo: 1, version: 3 } };

function fake(result: unknown) {
  const calls: { path: string; req: { body: Record<string, unknown>; headers: Record<string, string> } }[] = [];
  const client = { post: async (path: string, req: never) => { calls.push({ path, req }); if (result instanceof Error) throw result; return result; } } as unknown as HttpClient;
  return { client, calls };
}
const ctx = { idempotencyKey: "k" };

describe("createHttpFindingCommands", () => {
  const cases: [FindingCommand, string, Record<string, unknown>][] = [
    [{ kind: "initialize" }, "/api/v1/findings/a%2Fb/workflow-initialization", { expectedVersion: 2 }],
    [{ kind: "confirmPerson", reason: "属实" }, "/api/v1/findings/a%2Fb/confirm/person", { expectedVersion: 2, reason: "属实" }],
    [{ kind: "confirmSafety", reason: "确认", grade: "MAJOR" }, "/api/v1/findings/a%2Fb/confirm/safety", { expectedVersion: 2, reason: "确认", grade: "MAJOR" }],
    [{ kind: "verify", decision: "PASS" }, "/api/v1/findings/a%2Fb/verifications", { expectedVersion: 2, decision: "PASS" }],
    [{ kind: "verify", decision: "FAIL", reason: "未整改" }, "/api/v1/findings/a%2Fb/verifications", { expectedVersion: 2, decision: "FAIL", reason: "未整改" }],
  ];
  it.each(cases)("%j → path and body", async (command, path, body) => {
    const f = fake(ok);
    const result = await createHttpFindingCommands(f.client).run("a/b", 2, command, ctx);
    expect(f.calls[0]).toMatchObject({ path, req: { body, headers: { "Idempotency-Key": "k" } } });
    expect(result).toEqual({ state: "SAFETY_CONFIRM_PENDING", roundNo: 1, version: 3 });
  });

  it("maps statuses and 409 sub-codes, keeping the traceId", async () => {
    const table: [number, string, string][] = [
      [400, "INVALID_REQUEST", "validation"], [401, "AUTH_REQUIRED", "unauthenticated"], [403, "ACTION_DENIED", "forbidden"],
      [404, "RESOURCE_NOT_VISIBLE", "forbidden"], [409, "VERSION_CONFLICT", "versionConflict"], [409, "IDEMPOTENCY_CONFLICT", "idempotencyConflict"],
      [409, "COMMAND_CONFLICT", "stateConflict"], [422, "WORKFLOW_ASSIGNMENT_UNRESOLVED", "workflow"], [503, "DEPENDENCY_UNAVAILABLE", "unavailable"],
    ];
    for (const [status, code, kind] of table) {
      const f = fake(new ApiError(status, code, "trace-1", false));
      await expect(createHttpFindingCommands(f.client).run("a", 1, { kind: "initialize" }, ctx)).rejects.toMatchObject({ kind, traceId: "trace-1" });
    }
  });
});
