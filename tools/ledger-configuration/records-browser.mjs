import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";

const { chromium, expect } = createRequire(new URL("../../frontend/package.json", import.meta.url))("@playwright/test");
const origin = process.env.RECORD_UI_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin) || process.env.RECORD_UI_DATABASE !== "msf_record_runtime_test") {
  throw new Error("Dedicated loopback ledger record database and HTTPS origin required");
}
const browser = await chromium.launch({ channel: "chrome" });
const key = "sample_" + randomUUID().replaceAll("-", "");
const name = "采购申请 " + key;
const base = "/api/v1/admin/ledger-configurations/" + key;
const runtime = "/api/v1/ledgers/" + key;
const copyKey = "copy_" + randomUUID().replaceAll("-", "");
const copyName = "批次验收 " + copyKey;
const copyBase = "/api/v1/admin/ledger-configurations/" + copyKey;
const copyRuntime = "/api/v1/ledgers/" + copyKey;
const definition = {
  name, fields: [{ key: "quantity", label: "数量", type: "number", required: true }],
  listFields: ["quantity"], queryFields: ["quantity"]
};
async function login(actor) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, baseURL: origin });
  const page = await context.newPage();
  await page.goto("/");
  await page.getByLabel("租户编码").fill("d1seed");
  await page.getByLabel("账号", { exact: true }).fill("d1seed-" + actor);
  await page.getByLabel("密码", { exact: true }).fill(process.env["RECORD_PASSWORD_" + actor.toUpperCase()]);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL(url => url.pathname.startsWith("/t/"));
  return page;
}
async function api(page, method, path, body, status = 200) {
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === "XSRF-TOKEN");
  const response = await page.request.fetch(path, { method, data: body, headers: { "X-XSRF-TOKEN": csrf.value } });
  assert.equal(response.status(), status, method + " " + path + " " + await response.text());
  return response.json();
}
async function permissions(admin, role, codes) {
  return api(admin, "PUT", "/api/v1/admin/roles/" + role.id + "/permissions",
    { expectedVersion: role.version, permissionCodes: codes });
}
try {
  const admin = await login("keeper");
  const reader = await login("reader");
  await api(admin, "POST", base + "/versions", { requestId: randomUUID(), expectedVersion: 0, definition });
  await api(admin, "POST", base + "/publish", { version: 1, expectedVersion: 1, expectedPublishedVersion: null });
  const codes = ["read", "create", "update", "delete"].map(action => "ledger.record." + key + "." + action);
  const catalog = (await api(admin, "GET", "/api/v1/admin/permissions")).items.map(item => item.code);
  assert.ok(codes.every(code => catalog.includes(code)));
  assert.ok(!(await api(reader, "GET", "/api/v1/ledgers")).items.some(item => item.key === key));
  const role = (await api(admin, "GET", "/api/v1/admin/roles")).roles.find(role => role.code === "D1-reader");
  let current = await permissions(admin, role, [...role.permissionCodes, ...codes]);
  try {
    const entry = (await api(reader, "GET", runtime));
    const prefix = new URL(reader.url()).pathname.split("/").slice(0, 3).join("/");
    await reader.goto(prefix + "/platform/ledgers");
    await expect(reader.getByRole("link", { name: "业务台账", exact: true })).toBeVisible();
    await reader.getByRole("button", { name, exact: true }).click();
    await reader.getByRole("button", { name: "新增记录", exact: true }).click();
    const form = reader.getByRole("region", { name: "新增记录", exact: true });
    await form.getByRole("combobox").selectOption(entry.organizations[0].id);
    await form.getByLabel("数量（必填）").fill("42");
    await form.getByRole("button", { name: "保存记录" }).click();
    await expect(reader.getByText("共 1 条记录")).toBeVisible();
    const query = () => api(reader, "POST", runtime + "/records/query", { page: 1, size: 1, filters: [] });
    const record = (await query()).records[0];
    assert.equal(record.values.quantity, 42);

    const v2 = { ...definition, fields: [{ ...definition.fields[0], label: "采购数量" }] };
    await api(admin, "POST", base + "/versions", { requestId: randomUUID(), expectedVersion: 1, definition: v2 });
    await api(admin, "POST", base + "/publish", { version: 2, expectedVersion: 2, expectedPublishedVersion: 1 });
    const adminPrefix = new URL(admin.url()).pathname.split("/").slice(0, 3).join("/");
    await admin.goto(adminPrefix + "/platform/ledger-configurations");
    await admin.getByRole("navigation", { name: "已配置台账" }).getByRole("button").filter({ hasText: name }).click();
    await admin.getByRole("button", { name: "复制版本 1为新台账", exact: true }).click();
    await expect(admin.getByLabel("台账标识")).toHaveValue("");
    await expect(admin.getByLabel("字段名称")).toHaveValue("数量");
    await admin.getByLabel("台账标识").fill(copyKey);
    await admin.getByLabel("台账名称").fill(copyName);
    const copyAction = async (label, endpoint) => {
      const [response] = await Promise.all([
        admin.waitForResponse(response => response.request().method() === "POST"
          && new URL(response.url()).pathname === copyBase + "/" + endpoint),
        admin.getByRole("button", { name: label, exact: true }).click()
      ]);
      assert.equal(response.status(), 200, await response.text());
      return response.json();
    };
    const copied = await copyAction("保存草稿", "versions");
    assert.equal(copied.version, 1);
    assert.equal(copied.publishedVersion, null);
    assert.deepEqual(copied.definition, { ...definition, name: copyName });
    const copyCodes = ["read", "create", "update", "delete"].map(action => "ledger.record." + copyKey + "." + action);
    assert.ok(!(await api(admin, "GET", "/api/v1/admin/permissions")).items.some(item => copyCodes.includes(item.code)));
    assert.ok(!(await api(reader, "GET", "/api/v1/ledgers")).items.some(item => item.key === copyKey));
    await admin.getByLabel("字段名称").fill("验收数量");
    assert.equal((await copyAction("保存草稿", "versions")).version, 2);
    assert.equal((await copyAction("发布当前版本", "publish")).publishedVersion, 2);
    const originalVersions = (await api(admin, "GET", base + "/versions")).versions;
    assert.equal(originalVersions.length, 2);
    assert.equal(originalVersions[0].definition.fields[0].label, "采购数量");
    assert.equal(originalVersions[0].publishedVersion, 2);
    assert.ok(!(await api(reader, "GET", "/api/v1/ledgers")).items.some(item => item.key === copyKey));
    await api(reader, "GET", copyRuntime, undefined, 403);
    current = await permissions(admin, current, [...current.permissionCodes, ...copyCodes.slice(0, 2)]);
    const copyEntry = await api(reader, "GET", copyRuntime);
    const copyQuery = () => api(reader, "POST", copyRuntime + "/records/query", { page: 1, size: 10, filters: [] });
    assert.equal((await copyQuery()).records.length, 0);
    await api(reader, "GET", copyRuntime + "/records/" + record.id, undefined, 404);
    const copiedRecord = await api(reader, "POST", copyRuntime + "/records", {
      requestId: randomUUID(), definitionVersion: 2, organizationId: copyEntry.organizations[0].id,
      values: { quantity: 7 }
    });
    assert.equal(copiedRecord.definitionVersion, 2);
    assert.notEqual(copiedRecord.id, record.id);
    await api(reader, "PUT", copyRuntime + "/records/" + copiedRecord.id,
      { requestId: randomUUID(), expectedVersion: 1, values: { quantity: 8 } }, 403);
    assert.equal((await query()).records[0].values.quantity, 42);
    assert.equal((await copyQuery()).records[0].values.quantity, 7);

    await reader.getByRole("button", { name: "查看记录" }).click();
    await reader.getByRole("button", { name: "编辑草稿" }).click();
    const detail = reader.getByRole("region", { name: "记录详情", exact: true });
    await detail.getByLabel("数量（必填）").fill("45");
    await detail.getByRole("button", { name: "保存记录" }).click();
    await expect(reader.getByRole("cell", { name: "45", exact: true })).toBeVisible();
    const item = await api(reader, "GET", runtime + "/records/" + record.id);
    assert.equal(item.record.definitionVersion, 1);
    assert.equal(item.record.version, 2);
    current = await permissions(admin, current, current.permissionCodes.filter(code => code !== codes[2]));
    await api(reader, "PUT", runtime + "/records/" + record.id, { requestId: randomUUID(), expectedVersion: 2, values: { quantity: 47 } }, 403);
    assert.equal((await query()).records[0].values.quantity, 45);
    await reader.getByRole("button", { name: "查看记录" }).click();
    reader.once("dialog", dialog => dialog.accept());
    await reader.getByRole("button", { name: "删除草稿" }).click();
    await expect(reader.getByText("共 0 条记录")).toBeVisible();
    assert.equal((await copyQuery()).records[0].values.quantity, 7);
    const copyVersions = (await api(admin, "GET", copyBase + "/versions")).versions;
    assert.deepEqual(copyVersions.map(version => version.version), [2, 1]);
    assert.equal(copyVersions[0].definition.fields[0].label, "验收数量");
    console.log("Chrome/HTTPS: copied saved V1 into an independent ledger, published separately, verified independent history, records and action grants; edited source V1 after V2 publication, revoked edit and deleted source draft.", key, copyKey);
  } finally {
    await permissions(admin, current, role.permissionCodes);
  }
} finally {
  await browser.close();
}
