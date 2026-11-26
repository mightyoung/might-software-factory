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

test.describe("通用台账触屏查询", () => {
  test.use({ hasTouch: true, viewport: { width: 390, height: 900 } });

  test("从通用台账触摸清除搜索项，保留其它筛选并等待明确提交", async ({ page }) => {
    const path = "apps/inventory/entities/widget";
    const permission = "widget.read";
    await page.route("**/api/v1/session/bootstrap", async (route) => {
      const response = await route.fetch();
      if (!response.ok()) { await route.fulfill({ response }); return; }
      const body = await response.json();
      body.data.applications.push({ id: "inventory", name: "库存", entryPath: path });
      body.data.permissions.push(permission);
      body.data.canonicalPermissions?.push(permission);
      await route.fulfill({ response, json: body });
    });
    const operation = "/api/v1/apps/inventory/entities/widget";
    const meta = { traceId: "test" };
    const queryShape = { nullable: false, selectable: true, sortable: true, maxLength: 36, enumValues: [] };
    await page.route(`**${operation}/definition?use=READ`, (route) => route.fulfill({
      json: {
        meta,
        data: {
          use: "READ", effectiveActions: [permission],
          definition: {
            appKey: "inventory", entityKey: "widget", definitionVersion: "1", title: "通用物品",
            fields: {
              code: { label: "编号", type: "TEXT", nullable: false, valueLabels: {} },
              state: { label: "状态", type: "ENUM", nullable: false, valueLabels: { NORMAL: "正常" } },
            },
            inputFields: {}, references: {},
            querySchema: {
              key: "widget", schemaVersion: "1", idField: "id", defaultFields: ["code", "state"],
              fields: {
                id: { ...queryShape, type: "UUID", operators: ["EQ"] },
                code: { ...queryShape, type: "TEXT", operators: ["CONTAINS"] },
                state: { ...queryShape, type: "ENUM", operators: ["EQ"], enumValues: ["NORMAL"] },
              },
            },
            layout: { list: ["code", "state"], detail: ["code"], create: [], edit: [], query: ["code", "state"] },
            actions: {
              read: { label: "查看", permission },
              create: { label: "新增", permission: "widget.create" },
              update: { label: "修改", permission: "widget.update" },
            },
          },
        },
      },
    }));
    const filters: unknown[] = [];
    await page.route(`**${operation}/query`, (route) => {
      filters.push(route.request().postDataJSON().query.filterAST);
      return route.fulfill({ json: { meta, data: { records: [], page: 1, size: 20, total: 0, hasMore: false } } });
    });

    await signIn(page);
    await page.getByRole("link", { name: "库存" }).click();
    await expect(page.getByRole("heading", { name: "通用物品" })).toBeVisible();
    await expect(page.getByText("共 0 条，第 1 页")).toBeVisible();
    await page.getByRole("combobox", { name: "主题" }).selectOption("dark");
    const search = page.getByRole("searchbox", { name: "编号" });
    const status = page.getByRole("combobox", { name: "状态", exact: true });
    await search.fill("WG-01");
    await status.selectOption("NORMAL");
    const clear = page.getByRole("button", { name: "清除 编号", exact: true });
    await expect(clear).toBeVisible();
    await scan(page, "通用台账触屏清除");
    await clear.tap();
    await expect(search).toHaveValue("");
    await expect(search).toBeFocused();
    await expect(status).toHaveValue("NORMAL");
    await expect(clear).toHaveCount(0);
    expect(filters).toHaveLength(1);
    await search.press("Enter");
    await expect.poll(() => filters.length).toBe(2);
    expect(filters[1]).toMatchObject({ conditions: [{ field: "state", operator: "EQ", value: "NORMAL" }] });
  });
});

test("查询和重置可由键盘激活并在两种主题保持可访问", async ({ page }) => {
  await signIn(page);
  const firstCode = (await page.locator(".ledger-row").first().locator("td").nth(1).textContent())!.trim();
  const originalCount = await page.locator(".ledger-row").count();
  const keyword = page.getByRole("searchbox", { name: "隐患编号" });
  const search = page.getByRole("button", { name: "查询", exact: true });
  const reset = page.getByRole("button", { name: "重置", exact: true });
  const queryPath = "/api/v1/entities/finding/query";
  for (const [theme, width] of [["light", 1440], ["dark", 390]] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await keyword.fill(firstCode);
    await search.focus();
    await expect(search).toBeFocused();
    await scan(page, `查询按钮（${theme}）`);
    const searched = page.waitForResponse(response => new URL(response.url()).pathname === queryPath);
    await page.keyboard.press("Enter");
    expect((await searched).status()).toBe(200);
    await expect(page.locator(".ledger-row")).toHaveCount(1);
    await reset.focus();
    const resetResponse = page.waitForResponse(response => new URL(response.url()).pathname === queryPath);
    await page.keyboard.press("Space");
    const response = await resetResponse;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON()).not.toHaveProperty("filterAST");
    await expect(keyword).toHaveValue("");
    await expect(page.locator(".ledger-row")).toHaveCount(originalCount);
    await expect(reset).toBeFocused();
  }
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

test("权限勾选与放弃修改支持键盘及双主题窄屏", async ({ page }) => {
  // Test-owned admin contract projection; this checks UI interaction, not server authorization.
  const permissionCodes = ["finding.read", "platform.permission.read", "platform.permission.manage"];
  await page.route("**/api/v1/session/bootstrap", async route => {
    const response = await route.fetch();
    if (!response.ok()) { await route.fulfill({ response }); return; }
    const body = await response.json();
    body.data.permissions = [...new Set([...body.data.permissions, ...permissionCodes])];
    body.data.applications.push({ id: "platform", name: "系统设置", entryPath: "platform/permissions" });
    await route.fulfill({ response, json: body });
  });
  await page.route("**/api/v1/applications/platform/menu", route => route.fulfill({ json: { data: {
    schemaVersion: 1, applicationId: "platform", title: "系统设置",
    nodes: [{ id: "permissions", label: "权限配置", path: "platform/permissions", permission: "platform.permission.read" }],
    permissions: permissionCodes, authorization: { tenantAuthEpoch: 1, memberAuthEpoch: 1, applicationVersion: 1 },
  } } }));
  await page.route("**/api/v1/admin/permissions", route => route.fulfill({ json: { items: [
    { code: "finding.read", application: "safety", label: "查看隐患" },
    { code: "risk.read", application: "safety", label: "查看风险管控" },
  ] } }));
  await page.route("**/api/v1/admin/roles", route => route.fulfill({ json: { roles: [
    { id: "ui-role", code: "reporter", name: "上报人", active: true, memberCount: 1, permissionCodes: ["finding.read"], version: "1" },
  ] } }));
  const writes: string[] = [];
  page.on("request", request => {
    if (request.method() === "PUT" && request.url().includes("/api/v1/admin/")) writes.push(request.url());
  });
  await signIn(page);
  await page.goto(new URL(page.url()).pathname.replace("safety/findings", "platform/permissions"));
  await expect(page.getByRole("heading", { name: "上报人" })).toBeVisible();
  const permission = page.getByRole("checkbox", { name: "查看风险管控" });
  const save = page.getByRole("button", { name: "保存", exact: true });
  for (const [theme, width] of [["light", 1440], ["dark", 390]] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await expect(save).toBeDisabled();
    await permission.focus();
    await page.keyboard.press("Space");
    await expect(permission).toBeChecked();
    await expect(save).toBeEnabled();
    await scan(page, `权限配置（${theme}）`);
    const discard = page.getByRole("button", { name: "放弃修改" });
    await discard.focus();
    await page.keyboard.press("Enter");
    await expect(permission).not.toBeChecked();
    await expect(save).toBeDisabled();
  }
  expect(writes).toEqual([]);
});

test("侧栏折叠刷新保留，快捷导航在双主题支持完整键盘焦点", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page);
  const heading = page.getByRole("heading", { name: "隐患台账" });
  const nav = page.getByRole("navigation", { name: "生产安全导航" });
  await expect(heading).toBeVisible();
  await expect(nav).toBeVisible();
  await page.getByRole("button", { name: "收起导航" }).click();
  await expect(nav).toHaveCount(0);
  await page.reload();
  await expect(heading).toBeVisible();
  const expand = page.getByRole("button", { name: "展开导航" });
  await expect(expand).toHaveAttribute("aria-expanded", "false");
  await expect(nav).toHaveCount(0);
  await expand.click();
  await expect(nav).toBeVisible();

  for (const [theme, shortcut, width] of [
    ["light", "Control+k", 1440], ["dark", "Meta+k", 1440],
    ["light", "Control+k", 390], ["dark", "Meta+k", 390],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
    expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth),
      `${theme} 主题不应出现页面级横向滚动`).toBe(false);
    const trigger = page.getByRole("button", { name: "快捷导航" });
    await trigger.focus();
    await page.keyboard.press(shortcut);
    const modal = page.getByRole("dialog", { name: "快捷导航" });
    const search = modal.getByRole("searchbox", { name: "搜索页面" });
    await expect(search).toBeFocused();
    await scan(page, `快捷导航（${theme}）`);
    await search.fill("隐患");
    const result = modal.getByRole("link", { name: "隐患管理" });
    await expect(result).toHaveAttribute("href", /\/t\/[^/]+\/safety\/findings$/);
    const close = modal.getByRole("button", { name: "关闭" });
    await page.keyboard.press("Shift+Tab");
    await expect(close).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(search).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(result).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(modal).toHaveCount(0);
    await expect(trigger).toBeFocused();
  }
});

test("编译后的 Astryx 品牌变量在明暗主题保持同源", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
  const html = page.locator("html");
  await expect(html).toHaveAttribute("data-astryx-theme", "might");
  const accent: string[] = [];
  for (const theme of ["light", "dark"] as const) {
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    await expect(html).toHaveAttribute("data-theme", theme);
    const result = await html.evaluate(element => {
      const style = getComputedStyle(element);
      return {
        accent: style.getPropertyValue("--color-accent").trim(),
        petrol: style.getPropertyValue("--petrol").trim(),
        ink: style.getPropertyValue("--color-text-primary").trim(),
        legacyInk: style.getPropertyValue("--ink").trim(),
      };
    });
    expect(result.petrol).not.toBe("");
    expect(result.accent).toBe(result.petrol);
    expect(result.ink).toBe(result.legacyInk);
    accent.push(result.accent);
    await scan(page, `Astryx 登录页（${theme}）`);
  }
  expect(accent[0]).not.toBe(accent[1]);
});
