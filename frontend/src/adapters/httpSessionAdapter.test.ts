import { describe, expect, it } from "vitest";
import { ApiError, type HttpClient } from "../api/httpClient";
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
  return { client: { get: run as HttpClient["get"], post: ((p: string) => run(p)) as HttpClient["post"] }, calls };
}

describe("createHttpSessionAdapter", () => {
  it("logs in then reads the authoritative bootstrap", async () => {
    const { client, calls } = fake({ "/api/v1/auth/login": () => undefined, "/api/v1/session/bootstrap": () => bootstrap });
    const session = await createHttpSessionAdapter(client).login({ username: "u", password: "p", tenantCode: "c" });
    expect(calls).toEqual(["/api/v1/auth/login", "/api/v1/session/bootstrap"]);
    expect(session).toMatchObject({ actor: { id: "a", displayName: "张三" }, tenant: { id: "t", name: "租户" }, permissions: ["safety.finding.read", "safety.finding.report"] });
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
});
