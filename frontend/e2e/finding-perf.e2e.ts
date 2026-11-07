import { expect, test } from "@playwright/test";

// 只读；文件名须排在 password.e2e.ts 之前（它会改共享模拟服务里的密码）。
// 对着模拟服务（零网络延迟、内存数据）的冒烟：只防前端自身的回归（同步长任务、重复请求），
// **不是**设计里“登录到可操作首屏 P95 ≤ 2s”的证据，那要在真实网络、真实后端和数据量下测。
test("登录到台账首行可见：时间与长任务，以及不会重复拉取 schema / bootstrap", async ({ page }) => {
  await page.addInitScript(() => {
    (window as unknown as { __longTasks: number[] }).__longTasks = [];
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) (window as unknown as { __longTasks: number[] }).__longTasks.push(entry.duration);
    }).observe({ type: "longtask", buffered: true });
  });
  const requests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/")) requests.push(`${request.method()} ${url.pathname}`);
  });

  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  const start = Date.now();
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  const elapsed = Date.now() - start;
  const longTasks = await page.evaluate(() => (window as unknown as { __longTasks: number[] }).__longTasks);

  test.info().annotations.push({ type: "login→首行(ms，模拟服务)", description: String(elapsed) });
  test.info().annotations.push({ type: "长任务(ms)", description: JSON.stringify(longTasks.map(Math.round)) });

  expect(elapsed).toBeLessThan(2000);
  expect(Math.max(0, ...longTasks)).toBeLessThan(500);
  // 登录后的首屏只应各取一次：schema、查询、菜单、bootstrap（不全量拉取别的东西）
  const count = (needle: string) => requests.filter((r) => r.includes(needle)).length;
  expect(count("/entities/finding/schema")).toBe(1);
  expect(count("/entities/finding/query")).toBeLessThanOrEqual(2); // StrictMode 开发模式下会挂载两次，生产构建为 1
  expect(count("/applications/safety/menu")).toBeLessThanOrEqual(2);
  expect(requests.filter((r) => r.includes("/organizations/reportable"))).toHaveLength(0); // 未打开上报表单不取
});
