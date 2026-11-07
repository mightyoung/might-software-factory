import { expect, test, type Request } from "@playwright/test";

// 只读；文件名须排在 password.e2e.ts 之前（它会改共享模拟服务里的密码）。
// 对着模拟服务（零网络延迟、内存数据）的冒烟：只防前端自身的回归（同步长任务、重复请求），
// **不是**设计里“登录到可操作首屏 P95 ≤ 2s”的证据，那要在真实网络、真实后端和数据量下测。
test("登录到台账首行可见：时间与长任务，每次查询复核当前定义且 bootstrap 不过量", async ({ page }) => {
  await page.addInitScript(() => {
    (window as unknown as { __longTasks: number[] }).__longTasks = [];
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) (window as unknown as { __longTasks: number[] }).__longTasks.push(entry.duration);
    }).observe({ type: "longtask", buffered: true });
  });
  const requests: string[] = [];
  type BootstrapAttempt = { phase: "restore" | "login"; status?: number; failure?: string };
  const bootstrapAttempts: BootstrapAttempt[] = [];
  const bootstrapByRequest = new Map<Request, BootstrapAttempt>();
  let loginSubmitted = false;
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (request.method() === "POST" && url.pathname === "/api/v1/auth/login") loginSubmitted = true;
    if (url.pathname.startsWith("/api/")) requests.push(`${request.method()} ${url.pathname}${url.search}`);
    if (request.method() === "GET" && url.pathname === "/api/v1/session/bootstrap") {
      const attempt: BootstrapAttempt = { phase: loginSubmitted ? "login" : "restore" };
      bootstrapAttempts.push(attempt);
      bootstrapByRequest.set(request, attempt);
    }
  });

  page.on("response", (response) => {
    const attempt = bootstrapByRequest.get(response.request());
    if (attempt) attempt.status = response.status();
  });
  page.on("requestfailed", (request) => {
    const attempt = bootstrapByRequest.get(request);
    if (attempt) attempt.failure = request.failure()?.errorText ?? "unknown";
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
  // 每次数据读取都先复核当前 READ 定义；StrictMode 可能额外取消一次激活请求，不以缓存规避授权。
  const count = (needle: string) => requests.filter((request) => request.includes(needle)).length;
  const definitionReads = () => count("GET /api/v1/entities/finding/definition?use=READ");
  const queries = () => count("POST /api/v1/entities/finding/query");
  const initialQueries = queries();
  const initialDefinitions = definitionReads();
  expect(initialQueries).toBeGreaterThan(0);
  expect(initialQueries).toBeLessThanOrEqual(2);
  expect(initialDefinitions).toBeGreaterThanOrEqual(initialQueries);
  expect(initialDefinitions).toBeLessThanOrEqual(2);
  expect(count("/entities/finding/schema")).toBe(0);
  // main.tsx 的开发 StrictMode 可启动两次匿名 restore；登录成功另需一次当前 bootstrap。
  // 分别计数，不能把匿名恢复尝试和登录后的授权读取混成“最多两次”。
  const restores = bootstrapAttempts.filter((attempt) => attempt.phase === "restore");
  const loginBootstraps = bootstrapAttempts.filter((attempt) => attempt.phase === "login");
  expect(restores.length).toBeGreaterThan(0);
  expect(restores.length).toBeLessThanOrEqual(2);
  await expect.poll(() => restores.filter((attempt) => attempt.status === undefined && attempt.failure === undefined).length).toBe(0);
  const completedRestores = restores.filter((attempt) => attempt.status !== undefined);
  expect(completedRestores.length).toBeGreaterThan(0);
  for (const attempt of completedRestores) expect(attempt.status).toBe(401);
  for (const attempt of restores.filter((entry) => entry.status === undefined)) expect(attempt.failure).toMatch(/ERR_ABORTED/i);
  expect(loginBootstraps).toHaveLength(1);
  expect(loginBootstraps[0]!.status).toBe(200);
  expect(loginBootstraps[0]!.failure).toBeUndefined();
  const bootstrapsAtFirstRow = bootstrapAttempts.length;
  test.info().annotations.push({ type: "bootstrap phases", description: JSON.stringify({ anonymousRestore: restores, afterLogin: loginBootstraps }) });
  expect(count("/applications/safety/menu")).toBeGreaterThan(0);
  expect(count("/applications/safety/menu")).toBeLessThanOrEqual(2);
  expect(count("/entities/finding/definition?use=CREATE")).toBe(0);
  expect(count("/organizations/reportable")).toBe(0);
  expect(count("/entities/finding/references/report-organization/query")).toBe(0);

  // 同一适配器再次查询也必须重新取得定义；只接受当前定义成功后才发数据请求。
  const refreshDefinition = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === "GET" && url.pathname === "/api/v1/entities/finding/definition" && url.searchParams.get("use") === "READ";
  });
  const refreshQuery = page.waitForResponse((response) => response.request().method() === "POST" && new URL(response.url()).pathname === "/api/v1/entities/finding/query");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  expect((await refreshDefinition).status()).toBe(200);
  expect((await refreshQuery).status()).toBe(200);
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  expect(definitionReads()).toBe(initialDefinitions + 1);
  expect(queries()).toBe(initialQueries + 1);
  expect(bootstrapAttempts).toHaveLength(bootstrapsAtFirstRow); // 刷新台账只复核定义，不重复恢复/登录会话。
  expect(bootstrapAttempts.filter((attempt) => attempt.phase === "login")).toHaveLength(1);
});
