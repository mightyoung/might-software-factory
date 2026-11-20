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
    console.log("Chrome/HTTPS: published definition, granted four actions, created and edited a V1 record after V2 publication, revoked edit, deleted draft.", key);
  } finally {
    await permissions(admin, current, role.permissionCodes);
  }
} finally {
  await browser.close();
}
