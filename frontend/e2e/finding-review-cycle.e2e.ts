import { expect, test, type Page } from "@playwright/test";

// MOCK wire integration only. The opt-in Node fixture uses three fixed accounts;
// it does not prove Java/Flowable authority, HTTPS, PostgreSQL or G6-08 acceptance.
const findingId = "33333333-3333-4333-8333-333333333333";
const detailPath = `/t/44444444-4444-4444-8444-444444444444/safety/findings?caseId=${findingId}`;
const base = `/api/v1/findings/${findingId}`;
async function login(page: Page, role: "person" | "safety" | "reporter") {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill(`mock-${role}`);
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  await page.goto(detailPath);
  await expect(page.getByText("MOCK V3：循环补充与两级复核").first()).toBeVisible();
}
async function act(page: Page, label: string, text: string, expectedState: string, grade?: string) {
  const panel = page.getByLabel("隐患详情面板");
  await panel.getByRole("button", { name: label, exact: true }).click();
  await panel.getByRole("textbox", { name: label === "补充说明" ? /补充说明/ : /意见/ }).fill(text);
  if (grade) await panel.getByLabel(/隐患等级/).selectOption(grade);
  else await expect(panel.getByLabel(/隐患等级/)).toHaveCount(0);
  await panel.getByRole("button", { name: `确认${label}`, exact: true }).click();
  await expect(panel.locator(".status-current")).toHaveText(expectedState);
}
async function readDetail(page: Page) {
  const response = await page.request.get(`/api/v1/entities/finding/${findingId}`);
  expect(response.ok()).toBe(true);
  return (await response.json()).data;
}

test("MOCK V3 HTTP: PERSON return → reporter supplement → SAFETY return → cycle3 two-stage approval, exact retry and reload", async ({ browser }) => {
  const person = await browser.newPage({ baseURL: "http://127.0.0.1:15173" });
  const safety = await browser.newPage({ baseURL: "http://127.0.0.1:15173" });
  const reporter = await browser.newPage({ baseURL: "http://127.0.0.1:15173" });
  const pages = [person, safety, reporter];
  const commands: { path: string; key: string | undefined; body: Record<string, unknown> }[] = [];
  for (const page of pages) page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && [base + "/review-decisions", base + "/supplements"].includes(path)) commands.push({ path, key: request.headers()["idempotency-key"], body: request.postDataJSON() });
  });
  try {
    await login(person, "person");
    await login(safety, "safety");
    await login(reporter, "reporter");
    await expect(reporter.getByRole("button", { name: "退回补充", exact: true })).toHaveCount(0);
    await expect(safety.getByRole("button", { name: "安全确认", exact: true })).toHaveCount(0);

    // Commit in the mock, then replace its first HTTP response with 503.
    // Only an explicit same-key retry may discover the committed receipt.
    let first = true;
    await person.route(`**${base}/review-decisions`, async route => {
      const response = await route.fetch();
      if (first) {
        first = false;
        expect(response.status()).toBe(200);
        return route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ code: "DEPENDENCY_UNAVAILABLE", retryable: true, fieldErrors: [] }) });
      }
      return route.fulfill({ response });
    });
    const personPanel = person.getByLabel("隐患详情面板");
    await personPanel.getByRole("button", { name: "退回补充", exact: true }).click();
    await personPanel.getByLabel(/意见/).fill("MOCK 第一次退回：补充现场范围");
    await personPanel.getByRole("button", { name: "确认退回补充" }).click();
    await expect(personPanel.getByRole("alert")).toContainText("操作尚未确认成功");
    await expect(personPanel.locator(".status-current")).toHaveText("待一人确认");
    await expect(personPanel.getByLabel(/意见/)).toBeDisabled();
    await personPanel.getByRole("button", { name: "重试", exact: true }).click();
    await expect(personPanel.locator(".status-current")).toHaveText("待上报人补充");
    expect(commands[0]).toEqual(commands[1]);
    await expect(personPanel.getByRole("button", { name: "补充说明", exact: true })).toHaveCount(0);
    expect(await readDetail(person)).toMatchObject({ version: 2, reviewCycle: 1, roundNo: 1, grade: "UNKNOWN" });

    await reporter.reload();
    await act(reporter, "补充说明", "MOCK 补充一：范围与现场情况", "待一人确认");
    expect(await readDetail(reporter)).toMatchObject({ version: 3, reviewCycle: 2, roundNo: 1, grade: "UNKNOWN" });
    await person.reload();
    await act(person, "确认发现", "MOCK 第二次一人确认通过", "待安全确认");
    await safety.reload();
    await act(safety, "退回补充", "MOCK 第二次退回：补充保护措施", "待上报人补充");
    expect(await readDetail(safety)).toMatchObject({ version: 5, reviewCycle: 2, roundNo: 1, grade: "UNKNOWN" });
    await expect(safety.getByRole("button", { name: "补充说明", exact: true })).toHaveCount(0);
    await reporter.reload();
    await act(reporter, "补充说明", "MOCK 补充二：保护措施", "待一人确认");
    expect(await readDetail(reporter)).toMatchObject({ version: 6, reviewCycle: 3, roundNo: 1, grade: "UNKNOWN" });
    await person.reload();
    await act(person, "确认发现", "MOCK 第三次一人确认通过", "待安全确认");
    await safety.reload();
    await act(safety, "安全确认", "MOCK 第三次安全确认通过", "待安全部门派单", "MAJOR");
    await safety.reload();
    expect(await readDetail(safety)).toMatchObject({ version: 8, reviewCycle: 3, roundNo: 1, grade: "MAJOR", description: "MOCK V3：循环补充与两级复核" });
    const historyResponse = await safety.request.get(base + "/review-history");
    expect(historyResponse.ok()).toBe(true);
    const history = (await historyResponse.json()).data;
    expect(history).toMatchObject({ currentVersion: 8, reviewCycle: 3, processDefinitionKey: "findingRemediationV3", availableReviewActions: [], hasMore: false });
    expect(history.entries).toHaveLength(7);
    expect(history.entries.map((entry: { reviewCycle: number }) => entry.reviewCycle)).toEqual([1, 1, 2, 2, 2, 3, 3]);
    expect(history.entries.filter((entry: { recordedGrade: string | null }) => entry.recordedGrade !== null)).toHaveLength(1);
    const historyPanel = safety.getByLabel("确认与补充记录");
    await expect(historyPanel.getByRole("heading", { level: 4 })).toHaveText(["第 1 次确认", "第 2 次确认", "第 3 次确认"]);
    await expect(historyPanel.getByText(/名称不可用/)).toHaveCount(7);
    await expect(historyPanel.getByText("本次记录等级：重大")).toHaveCount(1);
    await expect(historyPanel.getByText("MOCK 补充一：范围与现场情况")).toBeVisible();
    await expect(historyPanel.getByText("MOCK 补充二：保护措施")).toBeVisible();
    expect(commands).toHaveLength(8);
    expect(new Set(commands.map(command => command.key)).size).toBe(7);
    for (const command of commands) {
      expect(command.body).toHaveProperty("expectedVersion");
      expect(command.body).toHaveProperty("reviewCycle");
      if (command.body.stage === "SAFETY" && command.body.decision === "APPROVE") expect(command.body.grade).toBe("MAJOR");
      else expect(command.body).not.toHaveProperty("grade");
    }
  } finally {
    await Promise.all(pages.map(page => page.context().close()));
  }
});
