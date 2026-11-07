import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { openFindingReport } from "./findingForm";

// 只读：不创建 / 修改数据（finding.e2e.ts 依赖种子记录数）。文件名排在 finding 之前、password 之前。
const WCAG = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

async function scan(page: Page, label: string) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG).analyze();
  const summary = violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join(" | ")}`);
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

  await page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click();
  await expect(page.getByLabel("隐患详情面板")).toBeVisible();
  await scan(page, "详情");
  await page.getByRole("button", { name: "关闭详情" }).click();

  const report = await openFindingReport(page);
  await scan(page, "上报表单");
  await report.submit.click(); // 触发校验错误态
  await expect(page.getByText("请选择所属组织")).toBeVisible();
  await scan(page, "上报表单（校验错误态）");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: /模拟用户/ }).click();
  await page.getByRole("button", { name: "修改密码" }).click();
  await expect(page.getByRole("dialog", { name: "修改密码" })).toBeVisible();
  await scan(page, "改密对话框");
});

test("流程命令表单（验收：退回整改）", async ({ page }) => {
  await signIn(page);
  await page.getByLabel("状态").selectOption("ACCEPTANCE_PENDING");
  await page.getByRole("button", { name: "查询" }).click();
  await page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click();
  const detail = page.getByLabel("隐患详情面板");
  await detail.getByRole("button", { name: "退回整改" }).click();
  await expect(detail.getByLabel(/意见/)).toBeVisible();
  await scan(page, "命令表单");
  await detail.getByRole("button", { name: "确认退回整改" }).click(); // 空意见 → 错误态
  await expect(detail.getByText("请填写意见")).toBeVisible();
  await scan(page, "命令表单（校验错误态）");
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
