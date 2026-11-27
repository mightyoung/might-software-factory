import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const database = "msf_d2_issue_test";
const base = "/api/v1/tooling/loan-applications";
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;

export function checkTarget(env) {
  const origin = env.D2_ISSUE_ORIGIN ?? "";
  const port = Number(/^https:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})$/.exec(origin)?.[1]);
  if (!(port >= 1024 && port <= 65535) || env.D2_ISSUE_DATABASE !== database
      || env.D2_ISSUE_ACK !== "isolated-issuance-acceptance:" + database) {
    throw new Error("Dedicated loopback HTTPS issuance fixture required");
  }
  const scope = env.D2_ISSUE_SCOPE_ID ?? "";
  const tool = env.D2_WITHDRAW_TOOL_ID ?? "";
  if (!uuid.test(scope) || !uuid.test(tool) || scope === tool) throw new Error("Expected distinct fixture scope and tool IDs");
  for (const actor of ["READER", "KEEPER"]) {
    if (!env["D2_PASSWORD_" + actor]) throw new Error("Missing fixture account password");
  }
  return { origin, scope, tool };
}

async function run(env) {
  const { origin, scope, tool } = checkTarget(env);
  const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
  const { chromium, expect } = require("@playwright/test");
  const browser = await chromium.launch({ channel: "chrome" });
  async function login(actor) {
    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    await context.route("**/*", (route) => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    await page.goto(origin, { timeout: 20000 });
    assert.equal(new URL(page.url()).origin, origin);
    await page.getByLabel("租户编码").fill("d1seed");
    await page.getByLabel("账号", { exact: true }).fill(`d1seed-${actor}`);
    await page.getByLabel("密码", { exact: true }).fill(env[`D2_PASSWORD_${actor.toUpperCase()}`]);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page.getByText("工装管理", { exact: true }).first()).toBeVisible();
    const entry = page.getByText("我的工装借用单", { exact: true }).first();
    if (!await entry.isVisible()) {
      await page.getByRole("link", { name: "工装管理", exact: true }).first().click();
    }
    await expect(entry).toBeVisible();
    return { page, entry };
  }
  async function api(page, path, input, key = randomUUID()) {
    return page.evaluate(async ({ path, input, key }) => {
      const signal = AbortSignal.timeout(15000);
      const token = document.cookie.split("; ").find((cookie) => cookie.startsWith("XSRF-TOKEN="));
      if (input && !token) throw new Error("Fixture CSRF cookie is missing");
      const response = await fetch(path, input ? { signal, method: "POST", headers: {
        "Content-Type": "application/json", "X-XSRF-TOKEN": decodeURIComponent(token.slice(11)), "Idempotency-Key": key,
      }, body: JSON.stringify(input) } : { signal });
      return { status: response.status, body: await response.json() };
    }, { path, input, key });
  }
  try {
    const { page: reader, entry } = await login("reader");
    const { page: keeper } = await login("keeper");
    const directory = await api(reader, `${base}/candidates?custodyScopeId=${scope}`);
    const definition = await api(reader, `${base}/definition`);
    assert.equal(directory.status, 200);
    assert.equal(definition.status, 200);
    const version = definition.body.data.definitionVersion;
    assert.ok(version === "1" || version === "2");
    const organization = directory.body.data.departments.find(({ label }) => label.includes("D1-ORG-A"));
    assert.ok(organization);
    assert.ok(directory.body.data.tools.some(({ id }) => id === tool));
    const marker = `TL10-ISSUE-FIRST-${randomUUID()}`;
    const created = await api(reader, base, {
      definitionVersion: version, custodyScopeId: scope, organizationId: organization.id,
      purpose: marker, toolIds: [tool], ...(version === "2" ? { usageLocation: "TL-10 synthetic fixture" } : {}),
    });
    assert.equal(created.status, 200);
    const loan = created.body.data;
    assert.ok(uuid.test(loan.id));
    assert.equal(loan.state, "PENDING_ISSUE");
    assert.equal(loan.version, 1);
    await entry.click();
    await expect(reader.getByRole("heading", { name: "工装借用申请" })).toBeVisible();
    await reader.getByRole("button", { name: "刷新申请", exact: true }).click();
    await reader.getByRole("row").filter({ hasText: marker }).getByRole("button", { name: "查看申请" }).click();
    await reader.getByRole("button", { name: "撤销申请", exact: true }).click();
    const form = reader.getByRole("form", { name: "撤销借用申请" });
    await expect(form).toContainText("申请版本：1");

    // Keep the borrower on the old confirmation while a separate custodian commits.
    const issue = await api(keeper, `${base}/${loan.id}/issue`, { definitionVersion: version, expectedVersion: 1 });
    assert.equal(issue.status, 200);
    assert.equal(issue.body.data.state, "ISSUED");
    assert.equal(issue.body.data.version, 2);
    const cancelPath = `${base}/${loan.id}/cancel`;
    const response = reader.waitForResponse((candidate) => new URL(candidate.url()).pathname === cancelPath
      && candidate.request().method() === "POST");
    await form.getByRole("button", { name: "确认撤销" }).click();
    const refused = await response;
    assert.equal(refused.status(), 409);
    assert.equal((await refused.json()).code, "VERSION_CONFLICT");
    assert.deepEqual(refused.request().postDataJSON(), { definitionVersion: version, expectedVersion: 1 });
    await form.getByRole("button", { name: "读取最新版本并核对后重新提交" }).click();
    await expect(reader.getByRole("region", { name: "借用申请详情" })).toContainText("已发放 · 第 2 版");
    await expect(reader.getByRole("button", { name: "核对后重试原请求" })).toHaveCount(0);
    await expect(reader.getByRole("button", { name: "撤销申请", exact: true })).toHaveCount(0);
    console.log(JSON.stringify({ marker, loanId: loan.id, toolId: tool, issueStatus: 200, withdrawStatus: 409,
      rereadState: "ISSUED", rereadVersion: 2, databaseEvidence: "PENDING" }));
  } finally {
    await browser.close();
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await run(process.env);
}
