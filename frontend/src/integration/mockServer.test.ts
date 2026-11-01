import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createMockServer, type MockServer } from "../../tools/mock-server/server.mjs";
import { createHttpFindingLedger } from "../adapters/httpFindingLedger";
import { createHttpFindingSubmit } from "../adapters/httpFindingSubmit";
import { createHttpSessionAdapter } from "../adapters/httpSessionAdapter";
import { createHttpClient } from "../api/httpClient";
import type { FindingDraft } from "../domain/findingDraft";

// 真实的 httpClient 与三个 HTTP adapter 对着模拟服务跑。模拟服务按契约书面描述编写，不是后端：
// 这里验证的是前端的 CSRF / Cookie / 错误映射 / 请求形状自洽，不是与 Java 后端的兼容性。
let server: MockServer;
let base = "";
let jar = new Map<string, string>();

/** 最小 cookie 罐：SESSION 是 HttpOnly，只存在这里；XSRF-TOKEN 同步到 document.cookie，供 httpClient 读取。 */
const jarFetch: typeof fetch = async (input, init) => {
  const headers = new Headers(init?.headers);
  if (jar.size) headers.set("Cookie", [...jar].map(([k, v]) => `${k}=${v}`).join("; "));
  const response = await fetch(base + String(input), { ...init, headers });
  for (const line of response.headers.getSetCookie()) {
    const [pair, ...attrs] = line.split("; ");
    const eq = pair!.indexOf("=");
    const name = pair!.slice(0, eq);
    const cleared = attrs.some((a) => a.toLowerCase() === "max-age=0");
    if (cleared) jar.delete(name);
    else jar.set(name, pair!.slice(eq + 1));
    if (name === "XSRF-TOKEN") document.cookie = cleared ? "XSRF-TOKEN=; Max-Age=0; Path=/" : `${pair}; Path=/`;
  }
  return response;
};

const client = () => createHttpClient({ fetchImpl: jarFetch });
const caps = { applications: [{ id: "safety" as const, name: "生产安全", entryPath: "safety/findings" }], permissions: [] };
const draft: FindingDraft = { source: "INSPECTION", description: "防护罩松动", location: "B 工房", foundAt: "2026-10-30T09:00", urgency: "IMMEDIATE", interimMeasure: "" };
const KEY = () => crypto.randomUUID();

beforeAll(async () => {
  server = createMockServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));
beforeEach(() => {
  jar = new Map();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
});

async function signIn() {
  const c = client();
  await createHttpSessionAdapter(c, caps).login({ username: "demo", password: "demo", tenantCode: "demo" });
  return c;
}

describe("HTTP adapters × 契约模拟服务", () => {
  it("未登录读取当前会话 → restore 为 null；登录后可读，退出后再次为 null", async () => {
    const adapter = createHttpSessionAdapter(client(), caps);
    expect(await adapter.restore()).toBeNull();
    const session = await adapter.login({ username: "demo", password: "demo", tenantCode: "demo" });
    expect(session.tenant.name).toContain("演示租户");
    expect((await adapter.restore())?.actor.displayName).toBe("模拟用户");
    await adapter.logout();
    expect(await adapter.restore()).toBeNull();
  });

  it("错误凭证与缺租户码同为 invalid_credentials", async () => {
    const adapter = createHttpSessionAdapter(client(), caps);
    await expect(adapter.login({ username: "demo", password: "bad", tenantCode: "demo" })).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(adapter.login({ username: "demo", password: "demo" })).rejects.toMatchObject({ kind: "invalid_credentials" });
  });

  it("没有 CSRF 头的写请求被拒绝（403）", async () => {
    const error = await jarFetch("/api/v1/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    expect(error.status).toBe(403);
  });

  it("台账：分页、编号包含、视图过滤、详情与不可见详情", async () => {
    const ledger = createHttpFindingLedger(await signIn());
    const q = { keyword: "", state: "", reportedAt: { from: "", to: "" } };
    const all = await ledger.list({ query: q, page: { index: 1, size: 20 }, view: "all" });
    expect(all).toMatchObject({ total: 3, pageCount: 1 });
    expect(all.items[0]!.reportedAt >= all.items[2]!.reportedAt).toBe(true); // createdAt 倒序
    const acceptance = await ledger.list({ query: q, page: { index: 1, size: 20 }, view: "acceptance" });
    expect(acceptance.items.map((r) => r.state)).toEqual(["ACCEPTANCE_PENDING"]);
    const byCode = await ledger.list({ query: { ...q, keyword: all.items[0]!.code.slice(-4) }, page: { index: 1, size: 20 }, view: "all" });
    expect(byCode.items.length).toBeGreaterThan(0);
    expect((await ledger.get(all.items[1]!.id))?.code).toBe(all.items[1]!.code);
    expect(await ledger.get("00000000-0000-4000-8000-000000000000")).toBeNull();
  });

  it("未登录读台账 → unauthenticated", async () => {
    await expect(
      createHttpFindingLedger(client()).list({ query: { keyword: "", state: "", reportedAt: { from: "", to: "" } }, page: { index: 1, size: 20 }, view: "all" }),
    ).rejects.toMatchObject({ name: "FindingLedgerAccessError", kind: "unauthenticated" });
  });

  it("上报：选组织 → 创建 → 台账出现；同键重放返回同一条；同键异参 conflict", async () => {
    const c = await signIn();
    const submit = createHttpFindingSubmit(c);
    const orgs = await submit.listOrganizations!();
    expect(orgs.length).toBe(2);
    const key = KEY();
    const first = await submit.submit(draft, { idempotencyKey: key, organizationId: orgs[0]!.id });
    expect(first.code).toMatch(/^YH-\d{8}-\d{4}$/);
    expect(first.state).toBe("PERSON_CONFIRM_PENDING");
    const replay = await submit.submit(draft, { idempotencyKey: key, organizationId: orgs[0]!.id });
    expect(replay).toEqual(first);
    await expect(submit.submit({ ...draft, description: "另一条描述" }, { idempotencyKey: key, organizationId: orgs[0]!.id })).rejects.toMatchObject({ kind: "conflict" });
    const listed = await createHttpFindingLedger(c).list({ query: { keyword: "", state: "", reportedAt: { from: "", to: "" } }, page: { index: 1, size: 20 }, view: "all" });
    expect(listed.items.filter((r) => r.code === first.code)).toHaveLength(1);
    const record = (await createHttpFindingLedger(c).get(first.id))!;
    expect(record).toMatchObject({ source: "INSPECTION", urgency: "URGENT", location: "B 工房", state: "PERSON_CONFIRM_PENDING" });
  });

  it("服务端校验：越权组织与空白位置 → validation，带字段", async () => {
    const submit = createHttpFindingSubmit(await signIn());
    await expect(submit.submit(draft, { idempotencyKey: KEY(), organizationId: "99999999-9999-4999-8999-999999999999" })).rejects.toMatchObject({ kind: "validation" });
    // 前端表单会先拦住空白位置；这里直接绕过表单，确认服务端也拒绝且映射到“位置”字段。
    const orgs = await submit.listOrganizations!();
    await expect(submit.submit({ ...draft, location: "\u0085" }, { idempotencyKey: KEY(), organizationId: orgs[0]!.id })).rejects.toMatchObject({
      kind: "validation",
      fieldErrors: { location: expect.any(String) },
    });
  });
});
