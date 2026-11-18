// 文件名决定执行顺序：它会新增记录，必须排在依赖“共 3 条”的 finding.e2e.ts 之后、改密码的 password.e2e.ts 之前。
import { expect, test } from "@playwright/test";
import { openFindingReport, openInTab, selectReportOrganization } from "./findingForm";

const PNG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);

test("整改中的记录：选择 PNG → 隔离扫描 → 终结发布 → 提交整改；非图片被拦截", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.locator(".ledger-row").first()).toBeVisible();

  await page.getByLabel("状态").selectOption("RECTIFYING");
  await page.getByRole("button", { name: "查询" }).click();
  const tab = await openInTab(page, () => page.getByRole("button", { name: "详情" }).first().click());

  const file = tab.getByLabel(/选择图片/);
  await file.setInputFiles({ name: "notes.pdf", mimeType: "application/pdf", buffer: Buffer.from("x") });
  await expect(tab.getByRole("alert")).toContainText("只支持 PNG 或 JPEG");

  await file.setInputFiles({ name: "护栏.png", mimeType: "image/png", buffer: PNG });
  await expect(tab.getByRole("status").filter({ hasText: "护栏.png" })).toContainText("已终结", { timeout: 15_000 });
  await tab.getByLabel("整改说明").fill("已加装护栏并复测");
  await tab.getByRole("button", { name: "提交整改" }).click();
  await expect(tab.locator(".status-current")).toHaveText("待验收");
});

test("未成立的记录：基于此创建关联上报，新记录详情带关联行，可回看原记录", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.locator(".ledger-row").first()).toBeVisible();

  // 先造一条并在人员确认阶段判不成立（上报、详情都在新标签页里）
  const first = await openFindingReport(page);
  const tab = first.page;
  await selectReportOrganization(first.organization, { id: "11111111-1111-4111-8111-111111111111", name: "一车间", code: "WS-A" });
  await tab.getByLabel(/所在位置/).fill("关联测试工位");
  await tab.getByLabel(/隐患描述/).fill("关联测试：原记录");
  await first.submit.click();
  await tab.getByRole("button", { name: "查看详情" }).click();
  const detail = tab.getByLabel("隐患详情面板");
  await detail.getByRole("button", { name: "判定未成立" }).click();
  await detail.getByLabel(/意见/).fill("现场未发现");
  await detail.getByRole("button", { name: "确认判定未成立" }).click();
  await expect(detail.locator(".status-current")).toHaveText("不成立");

  // 基于此创建关联上报：又一个新标签页
  const relatedTab = await openInTab(tab, () => detail.getByRole("button", { name: "基于此创建关联上报" }).click());
  await expect(relatedTab.getByText(/基于未成立记录/)).toBeVisible();
  const related = relatedTab.getByRole("complementary", { name: "新建上报面板" });
  await related.getByRole("combobox").first().selectOption("11111111-1111-4111-8111-111111111111");
  await relatedTab.getByLabel(/所在位置/).fill("关联测试工位");
  await relatedTab.getByLabel(/隐患描述/).fill("关联测试：补充新证据");
  await related.getByRole("button", { name: "提交上报" }).click();
  await relatedTab.getByRole("button", { name: "查看详情" }).click();
  await expect(relatedTab.getByLabel("隐患详情面板").getByText("由一条未成立记录补充新证据而来")).toBeVisible();
  const originalTab = await openInTab(relatedTab, () => relatedTab.getByRole("button", { name: /查看原记录/ }).click());
  await expect(originalTab.getByLabel("隐患详情面板").locator(".status-current")).toHaveText("不成立");
});
