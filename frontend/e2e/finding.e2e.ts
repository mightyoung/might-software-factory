import { expect, test, type Page } from "@playwright/test";

async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
}

test("错误凭证被拒绝，且页面不含合成演示说明", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByLabel("合成演示说明")).toHaveCount(0);
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("wrong");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("alert")).toHaveText("账号或密码不正确。");
});

test("登录后显示服务端台账，刷新页面会话仍在，退出后回到登录页", async ({ page }) => {
  await signIn(page);
  await expect(page.getByText("共 3 条记录")).toBeVisible();
  await expect(page.getByText("合成演示")).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  await page.getByRole("button", { name: /模拟用户/ }).click();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
  // 退出后直接访问受保护地址，应回到登录页而不是展示台账。
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
});

test("上报：选组织、填写、提交，得到服务端编号，台账随之增加", async ({ page }) => {
  await signIn(page);
  const before = Number(/共 (\d+) 条/.exec((await page.getByText(/共 \d+ 条记录/).textContent()) ?? "")![1]);
  await page.getByRole("button", { name: "新建上报" }).click();

  // 未选组织直接提交 → 前端拦截，不发请求
  await page.getByLabel(/所在位置/).fill("端到端测试工位");
  await page.getByLabel(/隐患描述/).fill("端到端：护栏连接件松动");
  await page.getByRole("button", { name: "提交上报" }).click();
  await expect(page.getByText("请选择所属组织")).toBeVisible();

  await page.getByLabel(/所属组织/).selectOption({ label: "二车间" });
  await page.getByRole("button", { name: "提交上报" }).click();
  await expect(page.getByRole("status").filter({ hasText: "已提交上报" })).toContainText(/YH-\d{8}-\d{4}/);

  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(page.getByText(`共 ${before + 1} 条记录`)).toBeVisible();
});

test("按编号查询只保留匹配的记录", async ({ page }) => {
  await signIn(page);
  const firstCode = (await page.locator(".ledger-row").first().locator("td").nth(1).textContent())!.trim();
  await page.getByLabel("隐患编号").fill(firstCode);
  await page.getByRole("button", { name: "查询" }).click();
  await expect(page.getByText("共 1 条记录")).toBeVisible();
});
