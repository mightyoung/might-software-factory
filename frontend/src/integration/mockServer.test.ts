import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createMockServer, type MockServer, type MockNavigation } from "../../tools/mock-server/server.mjs";
import { createHttpFindingCommands } from "../adapters/httpFindingCommands";
import { createHttpFindingLedger } from "../adapters/httpFindingLedger";
import { createHttpFindingSubmit } from "../adapters/httpFindingSubmit";
import { createHttpSessionAdapter } from "../adapters/httpSessionAdapter";
import { createHttpClient } from "../api/httpClient";
import type { FindingDraft } from "../domain/findingDraft";

// 真实的 httpClient 与三个 HTTP adapter 对着模拟服务跑。模拟服务按契约书面描述编写，不是后端：
// 这里验证的是前端的 CSRF / Cookie / 错误映射 / 请求形状自洽，不是与 Java 后端的兼容性。
let server: MockServer;
let base = "";
let navigation: MockNavigation;
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
const draft: FindingDraft = { source: "INSPECTION", description: "防护罩松动", location: "B 工房", foundAt: "2026-10-30T09:00", urgency: "IMMEDIATE", interimMeasure: "" };
const KEY = () => crypto.randomUUID();

beforeAll(async () => {
  server = createMockServer({ navigation: () => navigation });
  await new Promise<void>((resolve, reject) => {
    const onError = (error: Error) => { console.error("Owned synthetic mock listen failed", error); reject(error); };
    server.once("error", onError);
    server.listen(0, "127.0.0.1", () => { server.off("error", onError); resolve(); });
  });
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
afterAll(async () => {
  if (server?.listening) await new Promise<void>((resolve) => server.close(() => resolve()));
});
beforeEach(() => {
  navigation = { enabled: true, permissions: ["finding.read", "finding.create"] };
  jar = new Map();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
});

async function signIn() {
  const c = client();
  await createHttpSessionAdapter(c).login({ username: "demo", password: "demo", tenantCode: "demo" });
  return c;
}

describe("HTTP adapters × 契约模拟服务", () => {
  it("synthetic definitions project READ/CREATE separately and reject nonfinite use or revoked grants", async () => {
    const c = await signIn();
    const read = await c.get<{ data: { use: string; actions: object; references: object; fields: Record<string, { presentation: { edit: unknown } }> } }>("/api/v1/entities/finding/definition?use=READ");
    expect(read.data.use).toBe("READ");
    expect(read.data.actions).toEqual({});
    expect(read.data.references).toEqual({});
    expect(Object.values(read.data.fields).every(field => field.presentation.edit === null)).toBe(true);
    const create = await createHttpFindingSubmit(c).loadDefinition!();
    expect(create.layout.create).toHaveLength(7);
    expect(create.actions["finding.create"]!.inputFields).toHaveLength(11);
    expect(create.fields.grade!.query.enumValues).toEqual(["UNKNOWN"]);
    expect(create.fields.grade!.presentation.valueLabels).toEqual({ UNKNOWN: "待专业评估" });
    await expect(c.get("/api/v1/entities/finding/definition?use=UPDATE")).rejects.toMatchObject({ status: 400 });
    await expect(c.get("/api/v1/entities/finding/definition?use=READ&use=CREATE")).rejects.toMatchObject({ status: 400 });
    navigation.permissions = ["finding.read"];
    await expect(createHttpFindingSubmit(c).loadDefinition!()).rejects.toMatchObject({ kind: "forbidden" });
    navigation.permissions = [];
    await expect(createHttpFindingLedger(c).list({ query: {}, page: { index: 1, size: 20 }, view: "all" })).rejects.toMatchObject({ kind: "forbidden" });
  });

  it("publishes the complete nine-state query enum and backend-owned labels over the actual mock HTTP definition route",async()=>{
    const c=await signIn();
    const body=await c.get<{data:{fields:{state:{query:{enumValues:string[]};presentation:{uiKind:string;valueLabels:Record<string,string>}}}}}>("/api/v1/entities/finding/definition?use=READ");
    const expected={"DISCOVERED":"已发现","PERSON_CONFIRM_PENDING":"待责任人确认","SAFETY_CONFIRM_PENDING":"待安全确认","REPORTER_SUPPLEMENT_PENDING":"待上报人补充","SAFETY_DISPATCH_PENDING":"待安全派单","RECTIFYING":"整改中","ACCEPTANCE_PENDING":"待验收","NOT_ESTABLISHED":"不成立","CLOSED":"已关闭"};
    expect(body.data.fields.state.query.enumValues).toEqual(Object.keys(expected));
    expect(body.data.fields.state.presentation.uiKind).toBe("STATUS");
    expect(body.data.fields.state.presentation.valueLabels).toEqual(expected);
  });

  it("synthetic organization lookup is bounded, UUID-cursored and literal; legacy fixture route remains explicit", async () => {
    const c = await signIn();
    const query = createHttpFindingSubmit(c).queryOrganizations!;
    const first = await query({ query: "", limit: 1 });
    expect(first.items).toHaveLength(1);
    expect(first.nextAfterId).toBe(first.items[0]!.id);
    const next = await query({ query: "", limit: 1, afterId: first.nextAfterId });
    expect(next.items).toHaveLength(1);
    expect(next.items[0]!.id).not.toBe(first.items[0]!.id);
    expect(next.nextAfterId).toBeUndefined();
    expect((await query({ query: "二车间", limit: 20 })).items.map(item => item.name)).toEqual(["二车间"]);
    expect((await query({ query: "%_\\", limit: 20 })).items).toEqual([]);
    const route = "/api/v1/entities/finding/references/report-organization/query";
    expect((await c.post<{ data: { items: unknown[] } }>(route, { body: { query: "", afterId: null } })).data.items).toHaveLength(2);
    await expect(c.post(route, { body: { query: "", limit: 51 } })).rejects.toMatchObject({ status: 400 });
    await expect(c.post(route, { body: { query: "", limit: 1, afterId: "invalid" } })).rejects.toMatchObject({ status: 400 });
    expect((await c.get<{ data: unknown[] }>("/api/v1/organizations/reportable")).data).toHaveLength(2);
    navigation.permissions = ["finding.read"];
    await expect(query({ query: "", limit: 20 })).rejects.toMatchObject({ kind: "forbidden" });
  });

  it("未登录读取当前会话 → restore 为 null；登录后可读，退出后再次为 null", async () => {
    const adapter = createHttpSessionAdapter(client());
    expect(await adapter.restore()).toBeNull();
    const session = await adapter.login({ username: "demo", password: "demo", tenantCode: "demo" });
    expect(session.tenant.name).toContain("演示租户");
    expect((await adapter.restore())?.actor.displayName).toBe("模拟用户");
    await adapter.logout();
    expect(await adapter.restore()).toBeNull();
  });

  it.each([null, false])("bootstrap omits absent/disabled entitlement (%s) without demo fallback", async (enabled) => {
    navigation = { enabled, permissions: ["finding.read", "finding.create"] };
    const adapter = createHttpSessionAdapter(client());
    expect(await adapter.login({ username: "demo", password: "demo", tenantCode: "demo" })).toMatchObject({ applications: [], permissions: [] });
    await expect(adapter.loadMenu("safety")).rejects.toMatchObject({ kind: "forbidden" });
  });

  it("fresh bootstrap/menu reflect revoked grants and dependency failure", async () => {
    const adapter = createHttpSessionAdapter(client());
    const session = await adapter.login({ username: "demo", password: "demo", tenantCode: "demo" });
    expect(session.permissions).toEqual(["safety.finding.read", "safety.finding.report"]);
    expect((await adapter.loadMenu("safety")).nodes).toHaveLength(1);
    navigation.permissions = ["finding.read"];
    expect((await adapter.restore())?.permissions).toEqual(["safety.finding.read"]);
    navigation.permissions = [];
    expect(await adapter.restore()).toMatchObject({ applications: [], permissions: [] });
    await expect(adapter.loadMenu("safety")).rejects.toMatchObject({ kind: "forbidden" });
    navigation.failure = 503;
    await expect(adapter.restore()).rejects.toMatchObject({ kind: "unavailable" });
    await expect(adapter.loadMenu("safety")).rejects.toMatchObject({ kind: "unavailable" });
  });

  it("错误凭证与缺租户码同为 invalid_credentials", async () => {
    const adapter = createHttpSessionAdapter(client());
    await expect(adapter.login({ username: "demo", password: "bad", tenantCode: "demo" })).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(adapter.login({ username: "demo", password: "demo" })).rejects.toMatchObject({ kind: "invalid_credentials" });
  });

  it("does not enable MOCK V3 fixture accounts without the explicit server option", async () => {
    await expect(createHttpSessionAdapter(client()).login({ username: "mock-person", password: "demo", tenantCode: "demo" }))
      .rejects.toMatchObject({ kind: "invalid_credentials" });
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
    const orgs = (await submit.queryOrganizations!({ query: "", limit: 20 })).items;
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
    expect(listed.items.find((r) => r.id === first.id)?.urgency).toBe(draft.urgency);
    const record = (await createHttpFindingLedger(c).get(first.id))!;
    expect(record).toMatchObject({ source: "INSPECTION", urgency: draft.urgency, location: "B 工房", state: "PERSON_CONFIRM_PENDING" });
  });

  it("服务端校验：越权组织与空白位置 → validation，带字段", async () => {
    const submit = createHttpFindingSubmit(await signIn());
    await expect(submit.submit(draft, { idempotencyKey: KEY(), organizationId: "99999999-9999-4999-8999-999999999999" })).rejects.toMatchObject({ kind: "validation" });
    // 前端表单会先拦住空白位置；这里直接绕过表单，确认服务端也拒绝且映射到“位置”字段。
    const orgs = (await submit.queryOrganizations!({ query: "", limit: 20 })).items;
    await expect(submit.submit({ ...draft, location: "\u0085" }, { idempotencyKey: KEY(), organizationId: orgs[0]!.id })).rejects.toMatchObject({
      kind: "validation",
      fieldErrors: { location: expect.any(String) },
    });
  });

  it("流程命令：确认 → 安全确认 → 版本递增；旧版本 409；状态不符 409；验收通过关闭、退回需要意见", async () => {
    const c = await signIn();
    const submit = createHttpFindingSubmit(c);
    const commands = createHttpFindingCommands(c);
    const ledger = createHttpFindingLedger(c);
    const orgs = (await submit.queryOrganizations!({ query: "", limit: 20 })).items;
    const created = await submit.submit(draft, { idempotencyKey: KEY(), organizationId: orgs[0]!.id });
    let record = (await ledger.get(created.id))!;
    expect(record.version).toBe(1);

    const stale = commands.run(created.id, 99, { kind: "confirmPerson", reason: "属实" }, { idempotencyKey: KEY() });
    await expect(stale).rejects.toMatchObject({ kind: "versionConflict" });
    const wrongState = commands.run(created.id, 1, { kind: "confirmSafety", reason: "x", grade: "GENERAL" }, { idempotencyKey: KEY() });
    await expect(wrongState).rejects.toMatchObject({ kind: "stateConflict" });
    await expect(commands.run(created.id, 1, { kind: "confirmPerson", reason: "  " }, { idempotencyKey: KEY() })).rejects.toMatchObject({ kind: "validation" });

    const key = KEY();
    const r1 = await commands.run(created.id, 1, { kind: "confirmPerson", reason: "属实" }, { idempotencyKey: key });
    expect(r1).toEqual({ state: "SAFETY_CONFIRM_PENDING", roundNo: 1, version: 2 });
    expect(await commands.run(created.id, 1, { kind: "confirmPerson", reason: "属实" }, { idempotencyKey: key })).toEqual(r1); // 同键重放
    await expect(commands.run(created.id, 1, { kind: "confirmPerson", reason: "不同" }, { idempotencyKey: key })).rejects.toMatchObject({ kind: "idempotencyConflict" });
    const r2 = await commands.run(created.id, 2, { kind: "confirmSafety", reason: "确认", grade: "MAJOR" }, { idempotencyKey: KEY() });
    expect(r2.state).toBe("SAFETY_DISPATCH_PENDING");
    record = (await ledger.get(created.id))!;
    expect(record).toMatchObject({ state: "SAFETY_DISPATCH_PENDING", version: 3, riskLabel: "重大" });

    const all = await ledger.list({ query: { keyword: "", state: "", reportedAt: { from: "", to: "" } }, page: { index: 1, size: 20 }, view: "acceptance" });
    const pending = all.items[0]!;
    await expect(commands.run(pending.id, pending.version!, { kind: "verify", decision: "FAIL" }, { idempotencyKey: KEY() })).rejects.toMatchObject({ kind: "validation" });
    const failed = await commands.run(pending.id, pending.version!, { kind: "verify", decision: "FAIL", reason: "未整改到位" }, { idempotencyKey: KEY() });
    expect(failed).toMatchObject({ state: "RECTIFYING", roundNo: pending.round + 1 });
  });

  // 本用例改变共享模拟服务的密码，必须放在本文件最后。
  it("改密：当前密码错 → invalid_credentials；成功后旧会话作废、旧密码不能登录、新密码可登录；限流", async () => {
    const adapter = () => createHttpSessionAdapter(client());
    const session = adapter();
    await session.login({ username: "demo", password: "demo", tenantCode: "demo" });
    await expect(session.changePassword!("not-it", "n".repeat(15))).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(session.changePassword!("demo", "short")).rejects.toMatchObject({ kind: "invalid_request" });

    const next = "a-new-long-password";
    await session.changePassword!("demo", next);
    expect(await session.restore()).toBeNull(); // 旧会话作废
    await expect(adapter().login({ username: "demo", password: "demo", tenantCode: "demo" })).rejects.toMatchObject({ kind: "invalid_credentials" });
    const again = adapter();
    await again.login({ username: "demo", password: next, tenantCode: "demo" });

    // 连续 5 次当前密码错误后进入限流（模拟服务的固定规则）
    for (let i = 0; i < 5; i += 1) await expect(again.changePassword!("wrong", "n".repeat(15))).rejects.toMatchObject({ kind: "invalid_credentials" });
    await expect(again.changePassword!("wrong", "n".repeat(15))).rejects.toMatchObject({ kind: "rate_limited", retryAfterSeconds: 2 });
  });
});
