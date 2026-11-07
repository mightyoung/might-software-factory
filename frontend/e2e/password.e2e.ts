import { expect, test } from "@playwright/test";

// 本文件改变共享模拟服务里的密码，文件名排在 finding.e2e.ts 之后（单 worker 按文件名顺序执行）；不要在它之后再加登录 demo/demo 的用例。
const NEW = "a-new-long-password";

test("修改密码：校验、当前密码错误、成功后旧会话作废并用新密码登录", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();

  await page.getByRole("button", { name: /模拟用户/ }).click();
  await page.getByRole("button", { name: "修改密码" }).click();
  const dialog = page.getByRole("dialog", { name: "修改密码" });

  // 本地校验：新密码过短、确认不一致
  await dialog.getByLabel("当前密码").fill("demo");
  await dialog.getByLabel("新密码", { exact: true }).fill("short");
  await dialog.getByLabel("再次输入新密码").fill("short");
  await dialog.getByRole("button", { name: "修改密码" }).click();
  await expect(dialog.locator(".field-error", { hasText: "新密码至少 15 个字符" })).toBeVisible();

  // 当前密码错误：服务端 401，前端据会话仍有效判定为密码错误，并留在对话框里
  await dialog.getByLabel("当前密码").fill("wrong-password");
  await dialog.getByLabel("新密码", { exact: true }).fill(NEW);
  await dialog.getByLabel("再次输入新密码").fill(NEW);
  await dialog.getByRole("button", { name: "修改密码" }).click();
  await expect(dialog.getByText("当前密码不正确")).toBeVisible();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();

  // 成功：回到登录页并提示
  await dialog.getByLabel("当前密码").fill("demo");
  await dialog.getByRole("button", { name: "修改密码" }).click();
  await expect(page.getByRole("status")).toContainText("密码已修改");
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();

  // 旧密码不能登录，新密码可以
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("alert")).toHaveText("账号或密码不正确。");
  await page.getByLabel("密码").fill(NEW);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
});
