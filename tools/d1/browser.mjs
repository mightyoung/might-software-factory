import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
const { chromium, expect } = require("@playwright/test");
const origin = process.env.D1_BROWSER_ORIGIN;
const database = process.env.D1_DATABASE;
const output = process.env.D1_BROWSER_OUTPUT;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? "")
    || !/^msf_d0_d1_[a-z0-9_]+_test$/.test(database ?? "")
    || process.env.D1_SEED_ACK !== `isolated-d1-seed-only:${database}` || !output) {
  throw new Error("Explicit isolated D1 HTTPS target and private evidence directory required");
}
mkdirSync(output, { recursive: true, mode: 0o700 });
const browser = await chromium.launch({ channel: "chrome" });
const contexts = [];
const checks = [];
const apiBase = "/api/v1/apps/tooling/entities/tool";

async function login(actor) {
  const password = process.env[`D1_SEED_PASSWORD_${actor.toUpperCase()}`];
  if (!password) throw new Error(`Missing private password for ${actor}`);
  // Only the task-owned loopback endpoint uses an untrusted development certificate.
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(origin);
  await expect(page.getByLabel("合成演示说明")).toHaveCount(0);
  await page.getByLabel("租户编码").fill("d1seed");
  await page.getByLabel("账号", { exact: true }).fill(`d1seed-${actor}`);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: actor === "admin" ? "权限配置" : "正常工装档案", exact: true })).toBeVisible();
  return page;
}

async function post(page, path, body, key = crypto.randomUUID()) {
  return page.evaluate(async ({ path, body, key }) => {
    await fetch("/api/v1/auth/csrf");
    const cookie = document.cookie.split("; ").find(value => value.startsWith("XSRF-TOKEN="));
    if (!cookie) throw new Error("Missing CSRF cookie");
    const response = await fetch(path, { method: "POST", headers: {
      "Content-Type": "application/json", "Idempotency-Key": key,
      "X-XSRF-TOKEN": decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)),
    }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }, { path, body, key });
}

function saveResponse(page) {
  return page.waitForResponse(response => response.request().method() === "POST"
    && new URL(response.url()).pathname.startsWith(`${apiBase}/records`));
}

async function fillCreate(page, code, name) {
  await expect(page.getByRole("heading", { name: "新增", exact: true })).toBeVisible();
  await page.getByLabel(/^工装编码/).fill(code);
  await page.getByLabel(/^名称/).fill(name);
  await page.getByLabel("型号", { exact: true }).fill("D1-MODEL");
  await page.getByLabel("说明", { exact: true }).fill("Isolated real browser acceptance");
  const scope = page.getByRole("combobox", { name: "保管范围", exact: true });
  await expect(scope.locator("option")).toHaveCount(2);
  await expect(scope.locator("option").nth(1)).toContainText("D1-SCOPE-A");
  await scope.selectOption({ index: 1 });
}

let created;
try {
  const keeper = await login("keeper");
  await keeper.reload();
  await expect(keeper.getByRole("heading", { name: "正常工装档案", exact: true })).toBeVisible();
  const entryUrl = keeper.url();
  const run = Date.now().toString();
  const code = `BROWSER-${run}`;
  await keeper.getByRole("button", { name: "新增", exact: true }).click();
  await fillCreate(keeper, code, `Browser tool ${run}`);
  const creation = saveResponse(keeper);
  await keeper.getByRole("button", { name: "保存", exact: true }).click();
  const response = await creation;
  expect(response.status()).toBe(200);
  created = (await response.json()).data;
  expect(Object.keys(created).sort()).toEqual(["commandId", "definitionVersion", "eventId", "id", "occurredAt", "replay", "version"].sort());
  const originalCreate = response.request().postDataJSON();
  await expect(keeper.getByRole("heading", { name: "保存成功", exact: true })).toBeVisible();
  await keeper.getByRole("button", { name: "查看已保存记录", exact: true }).click();
  await keeper.reload();
  await expect(keeper.getByText(code, { exact: true })).toBeVisible();
  const detailUrl = keeper.url();
  checks.push("compiled frontend: real HTTPS login, create, persisted detail after reload");

  const stale = await keeper.context().newPage();
  await stale.goto(`${detailUrl}/edit`);
  await expect(stale.getByRole("heading", { name: "编辑", exact: true })).toBeVisible();
  await stale.getByLabel(/^名称/).fill(`Stale draft ${run}`);
  await keeper.getByRole("button", { name: "编辑", exact: true }).click();
  await keeper.getByLabel(/^名称/).fill(`Edited tool ${run}`);
  const saving = saveResponse(keeper);
  await keeper.getByRole("button", { name: "保存", exact: true }).click();
  expect((await saving).status()).toBe(200);
  await keeper.getByRole("button", { name: "查看已保存记录", exact: true }).click();
  await keeper.reload();
  await expect(keeper.getByText(`Edited tool ${run}`, { exact: true })).toBeVisible();
  const conflicting = saveResponse(stale);
  await stale.getByRole("button", { name: "保存", exact: true }).click();
  expect((await conflicting).status()).toBe(409);
  await expect(stale.getByRole("alert")).toContainText("记录已被其他人修改");
  await expect(stale.getByLabel(/^名称/)).toHaveValue(`Stale draft ${run}`);
  await stale.screenshot({ path: `${output}/version-conflict.png`, fullPage: true });
  checks.push("two real tabs: edit persists; stale version rejected and draft preserved");

  await keeper.getByRole("button", { name: "编辑", exact: true }).click();
  await keeper.getByLabel(/^名称/).fill(`Recovered tool ${run}`);
  let lostKey;
  let committed;
  const updatePattern = `**${apiBase}/records/${created.id}/update`;
  const loseReply = async route => {
    lostKey = route.request().headers()["idempotency-key"];
    const actual = await route.fetch();
    expect(actual.status()).toBe(200);
    committed = (await actual.json()).data;
    // The real server committed. Only the response transport is deliberately lost.
    await route.abort("failed");
  };
  await keeper.route(updatePattern, loseReply);
  await keeper.getByRole("button", { name: "保存", exact: true }).click();
  await expect(keeper.getByRole("alert")).toContainText("提交结果尚未确认");
  await expect(keeper.getByLabel(/^名称/)).toBeDisabled();
  await expect(keeper.getByLabel(/^名称/)).toHaveValue(`Recovered tool ${run}`);
  await keeper.unroute(updatePattern, loseReply);
  const retry = saveResponse(keeper);
  await keeper.getByRole("button", { name: "核对后重试原请求", exact: true }).click();
  const retried = await retry;
  expect(retried.status()).toBe(200);
  expect(retried.request().headers()["idempotency-key"]).toBe(lostKey);
  const recovered = (await retried.json()).data;
  expect(recovered.replay).toBe(true);
  expect(recovered.commandId).toBe(committed.commandId);
  expect(recovered.eventId).toBe(committed.eventId);
  expect(recovered.version).toBe(3);
  checks.push("real commit with lost reply: locked draft, explicit same-key retry, original receipt without duplicate revision");

  const reader = await login("reader");
  await reader.goto(detailUrl);
  await expect(reader.getByText(code, { exact: true })).toBeVisible();
  await expect(reader.getByRole("button", { name: "编辑", exact: true })).toHaveCount(0);
  await expect(reader.getByRole("button", { name: "新增", exact: true })).toHaveCount(0);
  const nonkeeper = await login("nonkeeper");
  await nonkeeper.goto(detailUrl);
  await expect(nonkeeper.getByText(code, { exact: true })).toBeVisible();
  await expect(nonkeeper.getByRole("button", { name: "编辑", exact: true })).toHaveCount(0);
  const denied = await post(nonkeeper, `${apiBase}/records/${created.id}/update`, {
    definitionVersion: created.definitionVersion, expectedVersion: 3,
    values: { name: "must not save", model: null, description: null },
  });
  expect([403, 404]).toContain(denied.status);
  checks.push("ordinary reader needs no keeper; action permission without keeper cannot edit or write");

  const only = await login("createonly");
  const requests = [];
  only.on("request", request => {
    if (new URL(request.url()).pathname.startsWith(apiBase)) requests.push({ method: request.method(), path: new URL(request.url()).pathname });
  });
  await fillCreate(only, `CREATEONLY-${run}`, `Create only ${run}`);
  const onlySave = saveResponse(only);
  await only.getByRole("button", { name: "保存", exact: true }).click();
  expect((await onlySave).status()).toBe(200);
  await expect(only.getByRole("heading", { name: "保存成功", exact: true })).toBeVisible();
  await expect(only.getByRole("button", { name: "查看已保存记录", exact: true })).toHaveCount(0);
  expect(requests.filter(request => request.path.endsWith("/query") && !request.path.includes("/references/")
    || request.method === "GET" && request.path.includes("/records/"))).toEqual([]);
  await only.screenshot({ path: `${output}/create-only.png`, fullPage: true });
  checks.push("create-only uses authorized candidates and minimal result; no list/detail read calls");

  const outside = await login("outside");
  await outside.goto(detailUrl);
  await expect(outside.getByRole("alert")).toContainText("记录不可见");
  const outsideWrite = await post(outside, `${apiBase}/records`, { ...originalCreate,
    values: { ...originalCreate.values, code: `OUTSIDE-${run}` } });
  expect([400, 403, 404, 422]).toContain(outsideWrite.status);
  checks.push("other organization cannot read record or inject its custody reference");

  await keeper.goto(entryUrl);
  await keeper.getByLabel("工装编码", { exact: true }).fill(code);
  await keeper.getByRole("button", { name: "查询", exact: true }).click();
  await expect(keeper.getByRole("row").filter({ hasText: code })).toHaveCount(1);
  await expect(keeper.locator(".ledger-table tbody tr")).toHaveCount(1);
  await keeper.screenshot({ path: `${output}/catalog-query.png`, fullPage: true });
  checks.push("definition-driven query returns the persisted authorized record");

  const result = { status: "PASS", checks, recordId: created.id, finalVersion: 3, detailUrl };
  writeFileSync(`${output}/summary.json`, JSON.stringify(result, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(result));
} catch (failure) {
  console.error(JSON.stringify({ status: "FAIL", checks, error: failure.message }));
  for (const context of contexts) for (const page of context.pages()) {
    console.error(JSON.stringify({ url: page.url(), text: (await page.locator("body").innerText()).slice(0, 1800) }));
  }
  process.exitCode = 1;
} finally {
  for (const context of contexts) await context.close();
  await browser.close();
}
