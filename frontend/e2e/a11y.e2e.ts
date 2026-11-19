import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { openFindingReport, openInTab } from "./findingForm";

// 只读：不创建 / 修改数据（finding.e2e.ts 依赖种子记录数）。文件名排在 finding 之前、password 之前。
const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

async function scan(page: Page, label: string) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG).analyze();
  const summary = violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => `${n.target.join(" ")} ${n.failureSummary ?? ""}`).slice(0, 3).join(" | ")}`);
  expect(summary, `${label} 的 WCAG A/AA 违规`).toEqual([]);
}

async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.locator(".ledger-row").first()).toBeVisible();
}

test("登录页", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
  await scan(page, "登录页");
});

test("台账、详情、上报表单、改密对话框", async ({ page }) => {
  await signIn(page);
  await scan(page, "台账");

  const detailTab = await openInTab(page, () => page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click());
  await expect(detailTab.getByLabel("隐患详情面板")).toBeVisible();
  await scan(detailTab, "详情");
  await detailTab.close();

  const report = await openFindingReport(page);
  await scan(report.page, "上报表单");
  await report.submit.click(); // 触发校验错误态
  await expect(report.page.getByText("请选择所属组织")).toBeVisible();
  await scan(report.page, "上报表单（校验错误态）");
  await report.page.close();

  await page.getByRole("button", { name: /模拟用户/ }).click();
  await page.getByRole("button", { name: "修改密码" }).click();
  await expect(page.getByRole("dialog", { name: "修改密码" })).toBeVisible();
  await scan(page, "改密对话框");
});

test("台账支持键盘逐行勾选、查看部分选中状态及整页切换", async ({ page }) => {
  await signIn(page);
  const first = page.locator(".ledger-row").first().getByRole("checkbox");
  const selectPage = page.getByRole("checkbox", { name: "选择本页记录" });
  for (const [theme, width] of [["light", 1440], ["dark", 390]] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await first.focus();
    await page.keyboard.press("Space");
    await expect(first).toBeChecked();
    await expect(selectPage).toBeChecked({ indeterminate: true });
    await scan(page, `台账部分选中（${theme}）`);
    await selectPage.focus();
    await page.keyboard.press("Space");
    await expect(selectPage).toBeChecked();
    await page.keyboard.press("Space");
    await expect(first).not.toBeChecked();
  }
});

test("查询框可用键盘清除编号并保留状态与日期条件", async ({ page }) => {
  await signIn(page);
  const keyword = page.getByRole("searchbox", { name: "隐患编号" });
  const state = page.getByRole("combobox", { name: "状态", exact: true });
  const from = page.getByLabel("上报日期起", { exact: true });
  const queryRequests: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname === "/api/v1/entities/finding/query") queryRequests.push(request.postData() ?? "");
  });
  await state.selectOption("RECTIFYING");
  await from.fill("2020-01-01T00:00");
  for (const [theme, width] of [["light", 1440], ["dark", 390]] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await keyword.fill("YH-尚未提交");
    const clear = page.getByRole("button", { name: "清除 隐患编号", exact: true });
    await expect(clear).toBeVisible();
    await scan(page, `查询清除按钮（${theme}）`);
    await keyword.focus();
    await page.keyboard.press("Tab");
    await expect(clear).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(keyword).toHaveValue("");
    await expect(keyword).toBeFocused();
    await expect(state).toHaveValue("RECTIFYING");
    await expect(from).toHaveValue("2020-01-01T00:00");
    await expect(clear).toHaveCount(0);
    expect(queryRequests).toEqual([]);
  }
  const response = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/entities/finding/query");
  await keyword.press("Enter");
  expect((await response).status()).toBe(200);
  expect(queryRequests).toHaveLength(1);
  expect(JSON.parse(queryRequests[0]).filterAST.conditions).toEqual([
    { field: "state", operator: "EQ", value: "RECTIFYING" },
    { field: "createdAt", operator: "GTE", value: "2020-01-01T00:00:00+08:00" },
  ]);
});

test("流程命令表单（验收：退回整改）", async ({ page }) => {
  await signIn(page);
  await page.getByLabel("状态").selectOption("ACCEPTANCE_PENDING");
  await page.getByRole("button", { name: "查询" }).click();
  const tab = await openInTab(page, () => page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click());
  const detail = tab.getByLabel("隐患详情面板");
  await detail.getByRole("button", { name: "退回整改" }).click();
  await expect(detail.getByLabel(/意见/)).toBeVisible();
  await scan(tab, "命令表单");
  await detail.getByRole("button", { name: "确认退回整改" }).click(); // 空意见 → 错误态
  await expect(detail.getByText("请填写意见")).toBeVisible();
  await scan(tab, "命令表单（校验错误态）");
});

test("键盘：改密对话框的焦点陷阱在真实浏览器里成立，Esc 关闭后焦点回到头像菜单按钮", async ({ page }) => {
  await signIn(page);
  const menuButton = page.getByRole("button", { name: /模拟用户/ });
  await menuButton.click();
  await page.getByRole("button", { name: "修改密码" }).click();
  const dialog = page.getByRole("dialog", { name: "修改密码" });
  await expect(dialog.getByLabel("当前密码")).toBeFocused();
  for (let i = 0; i < 12; i += 1) {
    await page.keyboard.press("Tab");
    expect(await dialog.evaluate((node) => node.contains(document.activeElement))).toBe(true);
  }
  for (let i = 0; i < 12; i += 1) {
    await page.keyboard.press("Shift+Tab");
    expect(await dialog.evaluate((node) => node.contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(menuButton).toBeFocused();
});
