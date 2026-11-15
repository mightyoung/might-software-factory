import { describe, expect, it } from "vitest";
import { ApiError, createHttpClient, type HttpClient } from "../api/httpClient";
import { createHttpSessionAdapter } from "./httpSessionAdapter";

const current = { data: { actorId: "a", accountId: "b", tenantId: "t", tenantCode: "c", tenantName: "租户", memberId: "m", displayName: "张三" } };
const bootstrap = { data: { schemaVersion: 1, identity: current.data,
  applications: [{ id: "safety", name: "生产安全", entryPath: "safety/findings" }],
  permissions: ["finding.read", "finding.create", "unknown", "safety.inbox.read"] } };
const menu = { data: { schemaVersion: 1, applicationId: "safety", title: "生产安全", permissions: ["finding.read"],
  nodes: [{ id: "findings", label: "隐患管理", path: "safety/findings", permission: "finding.read" },
    { id: "unknown", label: "未知", path: "safety/unknown", permission: "unknown" }] } };

function fake(handlers: Record<string, () => unknown>): { client: HttpClient; calls: string[] } {
  const calls: string[] = [];
  const run = async (path: string) => {
    calls.push(path);
    const result = handlers[path]!();
    if (result instanceof Error) throw result;
    return result;
  };
  return { client: { get: run as HttpClient["get"], post: ((p: string) => run(p)) as HttpClient["post"], put: ((p: string) => run(p)) as HttpClient["put"] }, calls };
}

describe("createHttpSessionAdapter", () => {
  it("logs in then reads the authoritative bootstrap", async () => {
    const { client, calls } = fake({ "/api/v1/auth/login": () => undefined, "/api/v1/session/bootstrap": () => bootstrap });
    const session = await createHttpSessionAdapter(client).login({ username: "u", password: "p", tenantCode: "c" });
    expect(calls).toEqual(["/api/v1/auth/login", "/api/v1/session/bootstrap"]);
    expect(session).toMatchObject({ identityScope: { accountId: "b", memberId: "m" }, actor: { id: "a", displayName: "张三" }, tenant: { id: "t", name: "租户" }, permissions: ["safety.finding.read", "safety.finding.report"] });
  });

  it("treats a missing tenant code and a 401 as the same invalid_credentials", async () => {
    const { client } = fake({ "/api/v1/auth/login": () => new ApiError(401, "AUTH_REQUIRED", "x", false) });
    const adapter = createHttpSessionAdapter(client);
    await expect(adapter.login({ username: "u", password: "p" })).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(adapter.login({ username: "u", password: "p", tenantCode: "c" })).rejects.toMatchObject({ kind: "invalid_credentials" });
  });

  it("restore returns null on 401 and unavailable on 503", async () => {
    const a401 = fake({ "/api/v1/session/bootstrap": () => new ApiError(401, "AUTH_REQUIRED", null, false) });
    expect(await createHttpSessionAdapter(a401.client).restore()).toBeNull();
    const a503 = fake({ "/api/v1/session/bootstrap": () => new ApiError(503, "DEPENDENCY_UNAVAILABLE", null, true) });
    await expect(createHttpSessionAdapter(a503.client).restore()).rejects.toMatchObject({ kind: "unavailable", retryable: true });
  });

  it.each([30, null])("preserves a 429 as rate_limited with Retry-After %s", async (seconds) => {
    const { client, calls } = fake({ "/api/v1/auth/login": () => new ApiError(429, "RATE_LIMITED", null, true, [], seconds) });
    await expect(createHttpSessionAdapter(client).login({ username: "u", password: "p", tenantCode: "c" }))
      .rejects.toMatchObject({ kind: "rate_limited", retryable: false, retryAfterSeconds: seconds });
    expect(calls).toEqual(["/api/v1/auth/login"]);
  });

  it.each([["17", 17], [undefined, null], ["999999999", 300], ["invalid", null]] as const)(
    "carries actual Retry-After header %s through HttpClient and adapter", async (header, expected) => {
      const client = createHttpClient({ readCookie: () => "csrf-token", fetchImpl: async () => new Response(
        JSON.stringify({ code: "RATE_LIMITED", message: "Too many attempts", retryable: true, fieldErrors: [] }),
        { status: 429, headers: { "Content-Type": "application/json", ...(header ? { "Retry-After": header } : {}) } },
      ) });
      await expect(createHttpSessionAdapter(client).login({ username: "u", password: "p", tenantCode: "c" }))
        .rejects.toMatchObject({ kind: "rate_limited", retryAfterSeconds: expected, retryable: false });
    });

  it.each([[401, "invalid_credentials"], [403, "forbidden"], [503, "unavailable"]] as const)(
    "keeps login HTTP %s mapped to %s", async (status, kind) => {
      const { client } = fake({ "/api/v1/auth/login": () => new ApiError(status, "DENIED", null, status === 503) });
      await expect(createHttpSessionAdapter(client).login({ username: "u", password: "p", tenantCode: "c" }))
        .rejects.toMatchObject({ kind, retryAfterSeconds: null });
    });

  it("loads server menu and maps only known permissions", async () => {
    const { client, calls } = fake({ "/api/v1/applications/safety/menu": () => menu });
    const result = await createHttpSessionAdapter(client).loadMenu("safety");
    expect(calls).toEqual(["/api/v1/applications/safety/menu"]);
    expect(result.nodes).toEqual([{ ...menu.data.nodes[0], permission: "safety.finding.read" }]);
  });

  it.each(["absent", "disabled"])("never invents applications for %s entitlement", async () => {
    const { client } = fake({ "/api/v1/session/bootstrap": () => ({ data: { ...bootstrap.data, applications: [], permissions: [] } }) });
    expect(await createHttpSessionAdapter(client).restore()).toMatchObject({ applications: [], permissions: [] });
  });

  it("filters unknown applications and does not grant unknown permission aliases", async () => {
    const { client } = fake({ "/api/v1/session/bootstrap": () => ({ data: { ...bootstrap.data,
      applications: [{ id: "unregistered", name: "未知", entryPath: "unknown" }], permissions: ["safety.finding.read", "__proto__"] } }) });
    expect(await createHttpSessionAdapter(client).restore()).toMatchObject({ applications: [], permissions: [] });
  });

  it("accepts a registered future application and preserves its canonical grants", async () => {
    const app = { id: "inventory", name: "库存", entryPath: "apps/inventory/entities/widget" };
    const f = fake({ "/api/v1/session/bootstrap": () => ({ data: { ...bootstrap.data, applications: [app], permissions: ["widget.read", "widget.create"] } }),
      "/api/v1/applications/inventory/menu": () => ({ data: { ...menu.data, applicationId: "inventory", permissions: ["widget.read"], nodes: [
        { id: "widgets", label: "物品", path: app.entryPath, permission: "widget.read" },
        { id: "cross", label: "跨应用", path: "apps/other/entities/widget", permission: "widget.read" },
        { id: "unsafe", label: "非法", path: "apps/inventory/entities/widget/../other", permission: "widget.read" },
      ] } }) });
    const adapter = createHttpSessionAdapter(f.client);
    expect(await adapter.restore()).toMatchObject({ applications: [app], canonicalPermissions: ["widget.read", "widget.create"] });
    expect((await adapter.loadMenu("inventory")).nodes).toHaveLength(1);
  });

  it("reads revocation afresh instead of caching prior menu grants", async () => {
    let granted = true;
    const { client } = fake({ "/api/v1/applications/safety/menu": () => granted ? menu : { data: { ...menu.data, permissions: [] } } });
    const adapter = createHttpSessionAdapter(client);
    expect((await adapter.loadMenu("safety")).nodes).toHaveLength(1);
    granted = false;
    expect((await adapter.loadMenu("safety")).nodes).toEqual([]);
  });

  it.each([401, 403, 503])("menu failure %s does not fall back to demo navigation", async (status) => {
    const { client } = fake({ "/api/v1/applications/safety/menu": () => new ApiError(status, "DENIED", null, status === 503) });
    await expect(createHttpSessionAdapter(client).loadMenu("safety")).rejects.toMatchObject({ kind: status === 401 ? "unauthenticated" : status === 403 ? "forbidden" : "unavailable" });
  });

  it("rejects unknown schema versions and mismatched application menus", async () => {
    const { client } = fake({ "/api/v1/session/bootstrap": () => ({ data: { ...bootstrap.data, schemaVersion: 2 } }),
      "/api/v1/applications/safety/menu": () => ({ data: { ...menu.data, applicationId: "equipment" } }) });
    const adapter = createHttpSessionAdapter(client);
    await expect(adapter.restore()).rejects.toMatchObject({ kind: "unavailable" });
    await expect(adapter.loadMenu("safety")).rejects.toMatchObject({ kind: "unavailable" });
  });

  describe("changePassword", () => {
    const P = "/api/v1/account/password";
    const run = (handlers: Record<string, () => unknown>) => createHttpSessionAdapter(fake(handlers).client).changePassword!("old", "n".repeat(15));

    it("sends the credentials once and resolves on 204", async () => {
      const f = fake({ [P]: () => undefined });
      await createHttpSessionAdapter(f.client).changePassword!("old", "n".repeat(15));
      expect(f.calls).toEqual([P]);
    });

    it("maps 400, 409 and 429", async () => {
      await expect(run({ [P]: () => new ApiError(400, "INVALID_REQUEST", null, false) })).rejects.toMatchObject({ kind: "invalid_request" });
      await expect(run({ [P]: () => new ApiError(409, "X", null, false) })).rejects.toMatchObject({ kind: "stale_attempt", retryable: true });
      await expect(run({ [P]: () => new ApiError(429, "RATE_LIMITED", null, true, [], 12) })).rejects.toMatchObject({ kind: "rate_limited", retryAfterSeconds: 12 });
    });

    it("tells a wrong current password from a dead session by re-reading the session on 401", async () => {
      const alive = fake({ [P]: () => new ApiError(401, "AUTH_REQUIRED", null, false), "/api/v1/session/bootstrap": () => bootstrap });
      await expect(createHttpSessionAdapter(alive.client).changePassword!("old", "n".repeat(15))).rejects.toMatchObject({ kind: "invalid_credentials" });
      const dead = fake({ [P]: () => new ApiError(401, "AUTH_REQUIRED", null, false), "/api/v1/session/bootstrap": () => new ApiError(401, "AUTH_REQUIRED", null, false) });
      await expect(createHttpSessionAdapter(dead.client).changePassword!("old", "n".repeat(15))).rejects.toMatchObject({ kind: "unauthenticated" });
      const down = fake({ [P]: () => new ApiError(401, "AUTH_REQUIRED", null, false), "/api/v1/session/bootstrap": () => new ApiError(503, "X", null, true) });
      await expect(createHttpSessionAdapter(down.client).changePassword!("old", "n".repeat(15))).rejects.toMatchObject({ kind: "unavailable" });
    });
  });
});
