import { describe, expect, it } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
import { createHttpSessionAdapter } from "./httpSessionAdapter";

const current = { data: { actorId: "a", accountId: "b", tenantId: "t", tenantCode: "c", tenantName: "租户", memberId: "m", displayName: "张三" } };
const caps = { applications: [{ id: "safety" as const, name: "生产安全", entryPath: "safety/findings" }], permissions: ["safety.finding.read"] };

function fake(handlers: Record<string, () => unknown>): { client: HttpClient; calls: string[] } {
  const calls: string[] = [];
  const run = async (path: string) => {
    calls.push(path);
    const result = handlers[path]!();
    if (result instanceof Error) throw result;
    return result;
  };
  return { client: { get: run as HttpClient["get"], post: ((p: string) => run(p)) as HttpClient["post"] }, calls };
}

describe("createHttpSessionAdapter", () => {
  it("logs in then reads the current session", async () => {
    const { client, calls } = fake({ "/api/v1/auth/login": () => undefined, "/api/v1/session/current": () => current });
    const session = await createHttpSessionAdapter(client, caps).login({ username: "u", password: "p", tenantCode: "c" });
    expect(calls).toEqual(["/api/v1/auth/login", "/api/v1/session/current"]);
    expect(session).toMatchObject({ actor: { id: "a", displayName: "张三" }, tenant: { id: "t", name: "租户" }, permissions: caps.permissions });
  });

  it("treats a missing tenant code and a 401 as the same invalid_credentials", async () => {
    const { client } = fake({ "/api/v1/auth/login": () => new ApiError(401, "AUTH_REQUIRED", "x", false) });
    const adapter = createHttpSessionAdapter(client, caps);
    await expect(adapter.login({ username: "u", password: "p" })).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(adapter.login({ username: "u", password: "p", tenantCode: "c" })).rejects.toMatchObject({ kind: "invalid_credentials" });
  });

  it("restore returns null on 401 and unavailable on 503", async () => {
    const a401 = fake({ "/api/v1/session/current": () => new ApiError(401, "AUTH_REQUIRED", null, false) });
    expect(await createHttpSessionAdapter(a401.client, caps).restore()).toBeNull();
    const a503 = fake({ "/api/v1/session/current": () => new ApiError(503, "DEPENDENCY_UNAVAILABLE", null, true) });
    await expect(createHttpSessionAdapter(a503.client, caps).restore()).rejects.toMatchObject({ kind: "unavailable", retryable: true });
  });

  it("has no menu source yet", async () => {
    const { client } = fake({});
    await expect(createHttpSessionAdapter(client, caps).loadMenu("safety")).rejects.toMatchObject({ kind: "unavailable" });
  });
});
