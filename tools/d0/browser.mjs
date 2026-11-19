import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";

const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
const { chromium, expect } = require("@playwright/test");
const origin = process.env.D0_BROWSER_ORIGIN;
const database = process.env.D0_DATABASE;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin ?? "")
    || !/^msf_d0_[a-z0-9_]+_test$/.test(database ?? "")
    || process.env.D0_BOOTSTRAP_ACK !== `isolated-d0-only:${database}`) {
  throw new Error("Explicit isolated D0 HTTPS browser target required");
}
const output = process.env.D0_BROWSER_OUTPUT;
if (!output) throw new Error("Private evidence directory required");
mkdirSync(output, { recursive: true, mode: 0o700 });
const tenant = process.env.D0_TENANT_CODE ?? "d0seed";
const browser = await chromium.launch({ channel: "chrome" });
const contexts = [];
const checks = [];

async function login(actor) {
  const password = process.env[`D0_SEED_PASSWORD_${actor.toUpperCase()}`];
  if (!password) throw new Error(`Missing seed password for ${actor}`);
  // Only this owned loopback endpoint uses a generated, untrusted development certificate.
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  contexts.push(context);
  const page = await context.newPage();
  await page.goto(origin);
  await expect(page.getByLabel("合成演示说明")).toHaveCount(0);
  await page.getByLabel("租户编码").fill(tenant);
  await page.getByLabel("账号", { exact: true }).fill(`${tenant}-${actor}`);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: actor === "admin" ? "权限配置" : "隐患台账", exact: true })).toBeVisible();
  return page;
}

try {
  const reporter = await login("reporter");
  await expect(reporter.getByText("正在加载记录…", { exact: true })).toHaveCount(0);
  await reporter.reload();
  await expect(reporter.getByRole("heading", { name: "隐患台账", exact: true })).toBeVisible();
  checks.push("real HTTPS login and persisted session");

  const definitionResponse = reporter.context().waitForEvent("response", response => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/entities/finding/definition" && url.searchParams.get("use") === "CREATE";
  });
  const popup = reporter.context().waitForEvent("page");
  await reporter.getByRole("button", { name: "新建上报", exact: true }).click();
  const report = await popup;
  await report.waitForLoadState();
  const definition = await (await definitionResponse).json();
  const organization = report.getByRole("combobox", { name: definition.data.fields.organizationId.presentation.label, exact: true });
  await expect(organization.locator("option").filter({ hasText: "D0 acceptance organization" })).toHaveCount(1);
  await organization.selectOption({ label: "D0 acceptance organization（D0-ORG）" });
  await report.getByLabel(/所在位置/).fill("D0 browser acceptance station");
  await report.getByLabel(/隐患描述/).fill(`D0 live browser ${new Date().toISOString()}: loose guard`);
  const createResponse = report.waitForResponse(response => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/findings");
  await report.getByRole("button", { name: definition.data.actions["finding.create"].label, exact: true }).click();
  const originalCreate = (await createResponse).request().postDataJSON();
  await expect(report.getByRole("status").filter({ hasText: "已提交上报" })).toBeVisible();
  await report.getByRole("button", { name: "查看详情", exact: true }).click();
  await expect(report.locator(".status-current")).toHaveText("待责任人确认");
  const detailUrl = report.url();
  await expect(report.getByRole("button", { name: "确认发现", exact: true })).toHaveCount(0);
  checks.push("create through published definition and real process; reporter cannot confirm");

  const person = await login("person");
  await person.goto(detailUrl);
  await person.getByRole("button", { name: "确认发现", exact: true }).click();
  await person.getByLabel(/意见/).fill("D0 real person confirmation");
  await person.getByRole("button", { name: "确认确认发现", exact: true }).click();
  await expect(person.locator(".status-current")).toHaveText("待安全确认");

  const safety = await login("safety");
  await safety.goto(detailUrl);
  await safety.getByRole("button", { name: "安全确认", exact: true }).click();
  await safety.getByLabel(/意见/).fill("D0 real safety confirmation");
  await safety.getByLabel(/隐患等级/).selectOption("GENERAL");
  await safety.getByRole("button", { name: "确认安全确认", exact: true }).click();
  await expect(safety.locator(".status-current")).toHaveText("待安全派单");
  await safety.getByRole("button", { name: "分派整改", exact: true }).click();
  await safety.getByLabel("按姓名开头搜索成员").fill("D0 remediator");
  await safety.getByLabel("D0 remediator", { exact: true }).check();
  const deadline = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 16);
  await safety.getByLabel(/整改期限/).fill(deadline);
  await safety.getByLabel(/整改要求/).fill("D0 repair guard and provide reviewed evidence");
  await safety.getByRole("button", { name: "确认分派整改", exact: true }).click();
  await expect(safety.locator(".status-current")).toHaveText("整改中");
  const remediator = await login("remediator");
  await remediator.goto(detailUrl);
  await expect(remediator.locator(".status-current")).toHaveText("整改中");
  await expect(remediator.getByRole("button", { name: "提交整改", exact: true })).toBeDisabled();
  await remediator.screenshot({ path: `${output}/live-workflow.png`, fullPage: true });
  checks.push("separate actors confirm and dispatch; missing evidence blocks submission");

  const admin = await login("admin");
  await admin.getByRole("button", { name: /^D0 reporter/ }).click();
  const create = admin.getByRole("checkbox", { name: /finding\.create/ });
  await expect(create).toBeChecked();
  await create.uncheck();
  await admin.getByRole("button", { name: "保存", exact: true }).click();
  await expect(admin.getByRole("status")).toContainText("已保存，权限即时生效");
  await reporter.reload();
  await expect(reporter.getByRole("button", { name: "新建上报", exact: true })).toHaveCount(0);
  const denied = await reporter.evaluate(async body => {
    await fetch("/api/v1/auth/csrf");
    const cookie = document.cookie.split("; ").find(value => value.startsWith("XSRF-TOKEN="));
    if (!cookie) throw new Error("CSRF cookie missing");
    const response = await fetch("/api/v1/findings", {
      method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID(),
        "X-XSRF-TOKEN": decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) }, body: JSON.stringify(body),
    });
    return response.status;
  }, originalCreate);
  expect(denied).toBe(403);
  checks.push("permission removal immediately hides create and denies direct API with 403");
  await create.check();
  await admin.getByRole("button", { name: "保存", exact: true }).click();
  await expect(admin.getByRole("status")).toContainText("已保存，权限即时生效");
  await reporter.reload();
  await expect(reporter.getByRole("button", { name: "新建上报", exact: true })).toBeVisible();
  await admin.getByRole("navigation", { name: "角色", exact: true }).getByRole("button", { name: /^D0 admin/ }).click();
  const manage = admin.getByRole("checkbox", { name: /platform\.permission\.manage/ });
  await manage.uncheck();
  await admin.getByRole("button", { name: "保存", exact: true }).click();
  await expect(admin.getByRole("alert")).toContainText("至少需要保留一个");
  await manage.check();
  await admin.screenshot({ path: `${output}/live-permissions.png`, fullPage: true });
  checks.push("permission restored; removing the last permission manager is rejected");
  console.log(JSON.stringify({ status: "PASS", checks, detailUrl }));
} catch (failure) {
  console.error(JSON.stringify({ status: "FAIL", checks, error: failure.message }));
  for (const context of contexts) for (const page of context.pages()) {
    console.error(JSON.stringify({ page: page.url(), text: (await page.locator("body").innerText()).slice(0, 2200) }));
  }
  process.exitCode = 1;
} finally {
  for (const context of contexts) await context.close();
  await browser.close();
}
