import { expect, test } from "@playwright/test";

test("演示构建使用本地会话和台账数据", async ({ page }) => {
  const apiRequests: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname.startsWith("/api/")) apiRequests.push(request.url());
  });
  await page.goto("/");
  await expect(page).toHaveTitle(/演示/);
  await page.getByLabel("账号", { exact: true }).fill("demo.safety");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  expect(apiRequests).toEqual([]);
});
