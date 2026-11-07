import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  await expect(page.locator(".ledger-row").first()).toBeVisible(); // 等列表加载完，否则“共 N 条”先显示 0
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

test("流程：新建上报 → 确认发现 → 安全确认（需选等级），状态与版本随服务端推进", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "新建上报" }).click();
  await page.getByLabel(/所属组织/).selectOption({ label: "一车间" });
  await page.getByLabel(/所在位置/).fill("流程测试工位");
  await page.getByLabel(/隐患描述/).fill("流程端到端：护栏松动");
  await page.getByRole("button", { name: "提交上报" }).click();
  await page.getByRole("button", { name: "查看详情" }).click();

  const detail = page.getByLabel("隐患详情面板");
  await expect(detail.getByText("待一人确认")).toBeVisible();
  await expect(detail.getByRole("button", { name: "分派整改" })).toHaveCount(0); // 当前节点不出现后续动作

  await detail.getByRole("button", { name: "确认发现" }).click();
  await detail.getByRole("button", { name: "确认确认发现" }).click();
  await expect(detail.getByText("请填写意见")).toBeVisible();
  await detail.getByLabel(/意见/).fill("现场核实属实");
  await detail.getByRole("button", { name: "确认确认发现" }).click();
  await expect(detail.getByRole("status").filter({ hasText: "已完成“确认发现”" })).toBeVisible();
  await expect(detail.locator(".status-current")).toHaveText("待安全确认");

  await detail.getByRole("button", { name: "安全确认", exact: true }).click();
  await detail.getByLabel(/意见/).fill("同意");
  await detail.getByRole("button", { name: "确认安全确认" }).click();
  await expect(detail.getByText("请选择隐患等级")).toBeVisible();
  await detail.getByLabel(/隐患等级/).selectOption("MAJOR");
  await detail.getByRole("button", { name: "确认安全确认" }).click();
  await expect(detail.locator(".status-current")).toHaveText("待安全部门派单");
  await expect(detail.locator(".status-muted")).toHaveText("重大");

  // 分派整改：搜索候选（按姓名开头）、选责任人、填期限与要求
  await detail.getByRole("button", { name: "分派整改" }).click();
  await detail.getByLabel("按姓名开头搜索成员").fill("李");
  await detail.getByLabel("李四").check();
  await expect(detail.getByText("已选：李四")).toBeVisible();
  // 分派表单（含候选列表）的无障碍扫描；其余界面见 a11y.e2e.ts
  const axe = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(" | ")}`)).toEqual([]);
  await detail.getByRole("button", { name: "确认分派整改" }).click();
  await expect(detail.getByText("请填写整改期限")).toBeVisible();
  await expect(detail.getByText("请填写整改要求")).toBeVisible();
  await detail.getByLabel(/整改期限/).fill("2027-01-15T18:00");
  await detail.getByLabel(/整改要求/).fill("加装防护罩并复测");
  await detail.getByRole("button", { name: "确认分派整改" }).click();
  await expect(detail.locator(".status-current")).toHaveText("整改中");
  // 当前轮已有责任人：不能再次分派，也没有“重新分派”之外的入口；提交整改证据仍缺上传接口
  await expect(detail.getByRole("button", { name: "提交整改证据" })).toBeDisabled();
});

test("不成立关闭：人员确认阶段填意见后终止，终态没有任何动作", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "新建上报" }).click();
  await page.getByLabel(/所属组织/).selectOption({ label: "一车间" });
  await page.getByLabel(/所在位置/).fill("不成立测试工位");
  await page.getByLabel(/隐患描述/).fill("不成立端到端：现场未发现");
  await page.getByRole("button", { name: "提交上报" }).click();
  await page.getByRole("button", { name: "查看详情" }).click();

  const detail = page.getByLabel("隐患详情面板");
  await detail.getByRole("button", { name: "不成立" }).click();
  await detail.getByRole("button", { name: "确认不成立" }).click();
  await expect(detail.getByText("请填写意见")).toBeVisible();
  await detail.getByLabel(/意见/).fill("现场核查未发现该隐患");
  await detail.getByRole("button", { name: "确认不成立" }).click();
  await expect(detail.locator(".status-current")).toHaveText("未成立");
  await expect(detail.getByText(/新证据应创建关联记录/)).toBeVisible();
  await expect(detail.getByRole("button", { name: /确认|分派|不成立/ })).toHaveCount(0);
});

test("验收：退回必须填意见，退回后进入下一轮整改", async ({ page }) => {
  await signIn(page);
  await page.getByLabel("状态").selectOption("ACCEPTANCE_PENDING");
  await page.getByRole("button", { name: "查询" }).click();
  await page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click();
  const detail = page.getByLabel("隐患详情面板");
  await expect(detail.locator(".status-current")).toHaveText("待安全验收");
  await detail.getByRole("button", { name: "退回整改" }).click();
  await detail.getByRole("button", { name: "确认退回整改" }).click();
  await expect(detail.getByText("请填写意见")).toBeVisible();
  await detail.getByLabel(/意见/).fill("防护罩仍未固定");
  await detail.getByRole("button", { name: "确认退回整改" }).click();
  await expect(detail.locator(".status-current")).toHaveText("整改中");
  await expect(detail.getByText(/第 2 轮/)).toBeVisible();
});

// Browser interception stays contract-mock evidence; it does not revoke real backend grants.
for (const entitlement of ["absent", "disabled"]) {
  test(`${entitlement} application has no static navigation fallback`, async ({ page }) => {
    await page.route("**/api/v1/session/bootstrap", async (route) => {
      const response = await route.fetch();
      if (!response.ok()) return route.fulfill({ response });
      const body = await response.json();
      body.data.applications = [];
      body.data.permissions = [];
      await route.fulfill({ response, json: body });
    });
    await page.goto("/");
    await page.getByLabel("租户编码").fill("demo");
    await page.getByLabel("账号").fill("demo");
    await page.getByLabel("密码").fill("demo");
    await page.getByRole("button", { name: "登录" }).click();
    await expect(page.getByRole("heading", { name: "没有可用应用" })).toBeVisible();
    await expect(page.getByRole("link", { name: "生产安全", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "新建上报" })).toHaveCount(0);
  });
}

test("revoked create grant is removed on restore and menu failure has no demo fallback", async ({ page }) => {
  await signIn(page);
  await page.route("**/api/v1/session/bootstrap", async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    body.data.permissions = ["finding.read", "unknown"];
    await route.fulfill({ response, json: body });
  });
  await page.route("**/api/v1/applications/safety/menu", (route) => route.fulfill({
    status: 503, contentType: "application/json", body: JSON.stringify({ code: "DEPENDENCY_UNAVAILABLE", retryable: true }),
  }));
  await page.reload();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  await expect(page.getByRole("button", { name: "新建上报" })).toHaveCount(0);
  await expect(page.getByText("导航加载失败。")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "安全管理导航" })).toHaveCount(0);
});

test("真实模式：台账、详情、上报表单的文字都不称数据为演示或合成，且不提供后端不接受的紧急程度", async ({ page }) => {
  await signIn(page);
  const body = page.locator("body");
  await expect(body).not.toContainText(/演示|合成|示例对象/);
  await page.locator(".ledger-row").first().getByRole("button", { name: "详情" }).click();
  await expect(page.getByLabel("隐患详情面板").getByRole("heading", { name: "处理轨迹" })).toBeVisible();
  await expect(body).not.toContainText(/演示|合成|示例对象/);
  await page.getByRole("button", { name: "关闭详情" }).click();
  await page.getByRole("button", { name: "新建上报" }).click();
  await expect(page.getByLabel("所属组织")).toBeVisible();
  await expect(page.getByLabel("需立即处置")).toBeVisible();
  await expect(page.getByLabel("紧急", { exact: true })).toHaveCount(0);
  await expect(body).not.toContainText(/演示|合成|示例对象/);
});
