import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";

const { chromium, expect } = createRequire(new URL("../../frontend/package.json", import.meta.url))("@playwright/test");
const origin = process.env.CONFIG_UI_ORIGIN;
if (!/^https:\/\/127\.0\.0\.1:\d+$/.test(origin) || process.env.CONFIG_UI_DATABASE !== "msf_config_test") {
  throw new Error("Set the dedicated loopback HTTPS origin and test database");
}
const key = "receipt_" + randomUUID().replaceAll("-", "");
const name = "批次验收 " + key;
const browser = await chromium.launch({ channel: "chrome" });
try {
  const page = await browser.newPage({ ignoreHTTPSErrors: true });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(origin);
  await page.getByLabel("租户编码").fill("d1seed");
  await page.getByLabel("账号", { exact: true }).fill("d1seed-keeper");
  await page.getByLabel("密码", { exact: true }).fill(process.env.CONFIG_UI_PASSWORD);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL(url => url.pathname.startsWith("/t/"));
  const tenantPath = new URL(page.url()).pathname.split("/").slice(0, 3).join("/");
  await page.goto(origin + tenantPath + "/platform/ledger-configurations");
  await expect(page.getByRole("link", { name: "台账配置", exact: true })).toBeVisible();
  const action = async (label, endpoint) => {
    const [response] = await Promise.all([
      page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname ===
        "/api/v1/admin/ledger-configurations/" + key + "/" + endpoint),
      page.getByRole("button", { name: label, exact: true }).click()
    ]);
    assert.equal(response.status(), 200, await response.text());
    return response.json();
  };
  await page.getByLabel("台账标识").fill(key);
  await page.getByLabel("台账名称").fill(name);
  await page.getByRole("button", { name: "添加字段" }).click();
  await page.getByLabel("字段标识").fill("batch");
  await page.getByLabel("字段名称").fill("批次");
  await page.getByRole("checkbox", { name: "列表显示" }).check();
  await page.getByRole("checkbox", { name: "允许查询" }).check();
  await page.getByRole("button", { name: "添加字段" }).click();
  await page.getByLabel("字段标识").nth(1).fill("quantity");
  await page.getByLabel("字段名称").nth(1).fill("数量");
  await page.getByLabel("字段类型").nth(1).selectOption("number");
  await page.getByRole("checkbox", { name: "列表显示" }).nth(1).check();
  await page.getByRole("checkbox", { name: "允许查询" }).nth(1).check();
  await page.getByRole("button", { name: "上移列表字段 数量（quantity）" }).click();
  const columns = page.getByRole("table", { name: "列表预览" }).getByRole("columnheader");
  await expect(columns).toHaveText(["数量", "批次"]);
  await expect(page.getByLabel("办理路径")).toHaveText("登记 → 完成");
  const first = await action("保存草稿", "versions");
  assert.equal(first.version, 1);
  assert.deepEqual(first.definition.listFields, ["quantity", "batch"]);
  assert.deepEqual(first.definition.queryFields, ["batch", "quantity"]);
  assert.equal((await action("发布当前版本", "publish")).publishedVersion, 1);
  await page.getByLabel("字段名称").first().fill("到货批次");
  await page.getByRole("button", { name: "插入办理节点" }).click();
  await page.getByLabel("节点名称").fill("主管确认");
  const node = page.getByRole("button", { name: "流程节点 主管确认", exact: true });
  const originalX = await page.getByLabel("节点横坐标").inputValue();
  const originalY = await page.getByLabel("节点纵坐标").inputValue();
  await node.scrollIntoViewIfNeeded();
  const box = await node.boundingBox();
  assert.ok(box);
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 35, box.y + box.height / 2 + 35, { steps: 5 });
  await page.mouse.up();
  const x = Number(await page.getByLabel("节点横坐标").inputValue());
  const y = Number(await page.getByLabel("节点纵坐标").inputValue());
  assert.notEqual(x, Number(originalX));
  assert.notEqual(y, Number(originalY));
  const second = await action("保存草稿", "versions");
  assert.equal(second.version, 2);
  assert.equal(second.definition.workflow.nodes.length, 3);
  assert.deepEqual(second.definition.workflow.nodes.find(node => node.kind === "step"), {
    id: second.definition.workflow.nodes.find(node => node.kind === "step").id,
    kind: "step", label: "主管确认", x, y,
  });
  await page.reload();
  await page.getByRole("navigation", { name: "已配置台账" }).getByRole("button").filter({ hasText: name }).click();
  await page.getByRole("button", { name: "流程节点 主管确认", exact: true }).click();
  await expect(page.getByLabel("节点横坐标")).toHaveValue(String(x));
  await expect(page.getByLabel("节点纵坐标")).toHaveValue(String(y));
  await page.getByRole("button", { name: "比较版本 1" }).click();
  await expect(page.getByRole("region", { name: "版本比较" })).toContainText("到货批次");
  const restored = await action("恢复版本 1为新草稿", "restore");
  assert.equal(restored.version, 3);
  assert.equal(restored.sourceVersion, 1);
  assert.equal(restored.publishedVersion, 1);
  assert.equal(restored.definition.fields[0].label, "批次");
  assert.deepEqual(restored.definition.workflow, first.definition.workflow);
  await expect(page.getByLabel("办理路径")).toHaveText("登记 → 完成");
  await expect(page.getByRole("button", { name: "流程节点 主管确认", exact: true })).toHaveCount(0);
  assert.equal((await action("发布当前版本", "publish")).publishedVersion, 3);
  await page.reload();
  await page.getByRole("navigation", { name: "已配置台账" }).getByRole("button").filter({ hasText: name }).click();
  await expect(page.getByText("版本 3 · 已发布", {exact:false}).first()).toBeVisible();
  await expect(columns).toHaveText(["数量", "批次"]);
  await expect(page.getByRole("group", { name: "查询条件预览" }).getByRole("spinbutton")).toBeDisabled();
  await expect(page.getByLabel("办理路径")).toHaveText("登记 → 完成");
  assert.deepEqual(errors, []);
  console.log("Real HTTPS and Chrome: saved default workflow, inserted/renamed/dragged a node in draft 2, reopened persisted coordinates, restored workflow 1 into draft 3 and explicitly published.", key);
} finally {
  await browser.close();
}
