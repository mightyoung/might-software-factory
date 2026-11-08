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
    [{ kind: "reviewDecision", reviewCycle: 2, stage: "PERSON", decision: "RETURN_FOR_INFORMATION", reason: "more" }, "/api/v1/findings/a%2Fb/review-decisions", { expectedVersion: 2, reviewCycle: 2, stage: "PERSON", decision: "RETURN_FOR_INFORMATION", reason: "more" }],
    [{ kind: "reviewDecision", reviewCycle: 3, stage: "SAFETY", decision: "APPROVE", reason: "yes", grade: "GENERAL" }, "/api/v1/findings/a%2Fb/review-decisions", { expectedVersion: 2, reviewCycle: 3, stage: "SAFETY", decision: "APPROVE", reason: "yes", grade: "GENERAL" }],
    [{ kind: "reviewDecision", reviewCycle: 3, stage: "SAFETY", decision: "NOT_ESTABLISHED", reason: "no" }, "/api/v1/findings/a%2Fb/review-decisions", { expectedVersion: 2, reviewCycle: 3, stage: "SAFETY", decision: "NOT_ESTABLISHED", reason: "no" }],
    [{ kind: "supplement", reviewCycle: 2, statement: "more" }, "/api/v1/findings/a%2Fb/supplements", { expectedVersion: 2, reviewCycle: 2, statement: "more" }],
    [{ kind: "closeNotEstablished", stage: "PERSON", reason: "no" }, "/api/v1/findings/a%2Fb/close-not-established", { expectedVersion: 2, stage: "PERSON", reason: "no" }],
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

  it("returns committed reviewCycle and reads paged history with opaque encoded cursor", async () => {
    const calls: string[] = [];
    const data = { entries: [], currentVersion: 4, reviewCycle: 2 };
    const client = { get: async (path: string) => { calls.push(path); return { data }; }, post: async () => ({ data: { ...ok.data, reviewCycle: 2 } }) } as unknown as HttpClient;
    const adapter = createHttpFindingCommands(client);
    expect(await adapter.history!("a/b", { cursor: "a+b/=" })).toEqual(data);
    expect(calls).toEqual(["/api/v1/findings/a%2Fb/review-history?pageSize=20&cursor=a%2Bb%2F%3D"]);
    expect(await adapter.run("f", 2, { kind: "supplement", reviewCycle: 1, statement: "statement" }, ctx)).toMatchObject({ reviewCycle: 2 });
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

describe("派单与不成立关闭", () => {
  it("dispatch / closeNotEstablished 的路径与请求体", async () => {
    const f = fake(ok);
    const commands = createHttpFindingCommands(f.client);
    await commands.run("a/b", 2, { kind: "dispatch", assigneeMemberId: "m1", dueAt: "2026-12-01T18:00:00+08:00", requirements: "加装防护罩" }, ctx);
    await commands.run("a/b", 2, { kind: "closeNotEstablished", stage: "SAFETY", reason: "未发现" }, ctx);
    expect(f.calls[0]).toMatchObject({ path: "/api/v1/findings/a%2Fb/dispatch", req: { body: { expectedVersion: 2, assigneeMemberId: "m1", dueAt: "2026-12-01T18:00:00+08:00", requirements: "加装防护罩" } } });
    expect(f.calls[1]).toMatchObject({ path: "/api/v1/findings/a%2Fb/close-not-established", req: { body: { expectedVersion: 2, stage: "SAFETY", reason: "未发现" } } });
  });

  function getClient(result: unknown) {
    const urls: string[] = [];
    const client = { get: async (path: string) => { urls.push(path); if (result instanceof Error) throw result; return result; } } as unknown as HttpClient;
    return { client, urls };
  }
  const body = { data: [{ memberId: "m1", displayName: "张三" }], findingVersion: 4, roundNo: 1, state: "SAFETY_DISPATCH_PENDING", hasMore: true, nextAfterMemberId: "m1" };

  it("候选查询：查询参数编码、只带有值的参数，返回映射后的分页", async () => {
    const g = getClient(body);
    const page = await createHttpFindingCommands(g.client).searchDispatchCandidates!("a/b", { prefix: "张 %_", afterMemberId: "m0", limit: 20 });
    expect(g.urls[0]).toBe("/api/v1/findings/a%2Fb/dispatch-candidates?q=%E5%BC%A0+%25_&limit=20&afterMemberId=m0");
    expect(page).toEqual({ items: [{ memberId: "m1", displayName: "张三" }], hasMore: true, next: "m1", findingVersion: 4, state: "SAFETY_DISPATCH_PENDING", roundNo: 1 });
    const bare = getClient(body);
    await createHttpFindingCommands(bare.client).searchDispatchCandidates!("f", {});
    expect(bare.urls[0]).toBe("/api/v1/findings/f/dispatch-candidates");
  });

  it("候选 409 / 403 / 401 映射为命令失败类别", async () => {
    for (const [status, kind] of [[409, "stateConflict"], [403, "forbidden"], [401, "unauthenticated"]] as const) {
      const g = getClient(new ApiError(status, "COMMAND_CONFLICT", "t", false));
      await expect(createHttpFindingCommands(g.client).searchDispatchCandidates!("f", {})).rejects.toMatchObject({ kind, traceId: "t" });
    }
  });
});
