import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

// Test-owned configuration API fixtures exercise real HTTP adapter and browser controls.
test("台账配置保存、比较、恢复与显式发布支持 Astryx 键盘操作", async ({
  page,
}) => {
  const editorRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/ledgerConfig/"))
      editorRequests.push(request.url());
  });
  const permissions = [
    "ledger.configuration.read",
    "ledger.configuration.manage",
    "ledger.configuration.publish",
  ];
  await page.route("**/api/v1/session/bootstrap", async (route) => {
    const response = await route.fetch();
    if (!response.ok()) {
      await route.fulfill({ response });
      return;
    }
    const body = await response.json();
    body.data.permissions.push(...permissions);
    body.data.applications.push({
      id: "platform",
      name: "系统设置",
      entryPath: "platform/ledger-configurations",
    });
    await route.fulfill({ response, json: body });
  });
  await page.route("**/api/v1/applications/platform/menu", (route) =>
    route.fulfill({
      json: {
        data: {
          schemaVersion: 1,
          applicationId: "platform",
          title: "系统设置",
          nodes: [
            {
              id: "ledger-config",
              label: "台账配置",
              path: "platform/ledger-configurations",
              permission: permissions[0],
            },
          ],
          permissions,
          authorization: {
            tenantAuthEpoch: 1,
            memberAuthEpoch: 1,
            applicationVersion: 1,
          },
        },
      },
    }),
  );
  type Definition = {
    name: string;
    fields: { key: string; label: string; type: string; required: boolean }[];
    listFields: string[];
    queryFields: string[];
  };
  type Snapshot = {
    key: string;
    version: number;
    definition: Definition;
    publishedVersion: number | null;
    sourceVersion: number | null;
    savedAt: string;
    savedBy: string;
  };
  const versions: Snapshot[] = [];
  let published: number | null = null;
  let loseNextSaveResponse = false;
  const receipts = new Map<string, { body: unknown; snapshot: Snapshot }>();
  await page.route("**/api/v1/admin/ledger-configurations**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "GET") {
      await route.fulfill({
        json: path.endsWith("/versions")
          ? { versions }
          : {
              items: versions.length
                ? [
                    {
                      ...versions[0],
                      latestVersion: versions[0]!.version,
                      publishedVersion: published,
                    },
                  ]
                : [],
            },
      });
      return;
    }
    const body = route.request().postDataJSON();
    if (path.endsWith("/publish")) {
      expect(body.expectedPublishedVersion).toBe(published);
      published = body.version;
      await route.fulfill({
        json: { ...versions[0], publishedVersion: published },
      });
      return;
    }
    const existing = receipts.get(body.requestId);
    if (existing) {
      expect(body).toEqual(existing.body);
      await route.fulfill({ json: existing.snapshot });
      return;
    }
    const definition = path.endsWith("/restore")
      ? versions.find((value) => value.version === body.sourceVersion)!
          .definition
      : body.definition;
    const next = {
      key: "purchase",
      version: versions.length + 1,
      definition,
      publishedVersion: published,
      sourceVersion: body.sourceVersion ?? null,
      savedAt: new Date().toISOString(),
      savedBy: "配置员",
    };
    versions.unshift(next);
    receipts.set(body.requestId, { body, snapshot: next });
    if (loseNextSaveResponse) {
      loseNextSaveResponse = false;
      await route.abort();
      return;
    }
    await route.fulfill({ json: next });
  });
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号").fill("demo");
  await page.getByLabel("密码").fill("demo");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  expect(editorRequests).toEqual([]);
  await page.goto(
    new URL(page.url()).pathname.replace(
      "safety/findings",
      "platform/ledger-configurations",
    ),
  );
  await page.getByLabel("台账标识").fill("purchase");
  expect(editorRequests.length).toBeGreaterThan(0);
  await page.getByLabel("台账名称").fill("采购申请");
  await page.getByRole("button", { name: "添加字段" }).click();
  await page.getByLabel("字段标识").fill("quantity");
  await page.getByLabel("字段名称").fill("数量");
  await page.getByLabel("字段类型").selectOption("number");
  await page.getByRole("checkbox", { name: "列表显示" }).focus();
  await page.keyboard.press("Space");
  await expect(page.getByRole("checkbox", { name: "列表显示" })).toBeChecked();
  await page.getByRole("checkbox", { name: "允许查询" }).click();
  for (const [key, label, type] of [
    ["due", "到期日期", "date"],
    ["approved", "已确认", "boolean"],
  ]) {
    await page.getByRole("button", { name: "添加字段" }).click();
    const field = page.locator(".config-field").last();
    await field.getByLabel("字段标识").fill(key);
    await field.getByLabel("字段名称").fill(label);
    await field.getByLabel("字段类型").selectOption(type);
    await field.getByRole("checkbox", { name: "列表显示" }).click();
    await field.getByRole("checkbox", { name: "允许查询" }).click();
  }
  await page.getByRole("button", { name: "上移列表字段 到期日期（due）", exact: true }).click();
  await page.getByRole("button", { name: "上移查询字段 已确认（approved）", exact: true }).focus();
  await page.keyboard.press("Enter");
  const assertPreview = async () => {
    await expect(page.getByRole("table", { name: "列表预览" }).getByRole("columnheader"))
      .toHaveText(["到期日期", "数量", "已确认"]);
    const query = page.getByRole("group", { name: "查询条件预览" });
    expect(await query.locator("input, select").evaluateAll((controls) =>
      controls.map((control) => control.getAttribute("type") ?? control.tagName.toLowerCase()),
    )).toEqual(["number", "select", "date"]);
    await expect(query.getByLabel("数量（查询预览）")).toHaveAttribute("type", "number");
    await expect(query.getByLabel("到期日期（查询预览）")).toHaveAttribute("type", "date");
    await expect(query.getByLabel("已确认（查询预览）")).toBeDisabled();
  };
  await assertPreview();
  expect(versions).toEqual([]);
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "已保存草稿版本 1" }),
  ).toBeVisible();
  expect(versions[0].definition.listFields).toEqual(["due", "quantity", "approved"]);
  expect(versions[0].definition.queryFields).toEqual(["quantity", "approved", "due"]);
  await page.reload();
  await page.getByRole("navigation", { name: "已配置台账" })
    .getByRole("button").filter({ hasText: "采购申请" }).click();
  await assertPreview();
  await page.getByLabel("台账名称").fill("采购申请新版");
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "已保存草稿版本 2" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "比较版本 1", exact: true }).click();
  await expect(page.getByRole("region", { name: "版本比较" })).toContainText(
    "配置内容不同",
  );
  await page.getByRole("button", { name: "恢复版本 1为新草稿" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "新草稿 3" }),
  ).toBeVisible();
  expect(published).toBeNull();
  await expect(page.getByLabel("台账名称")).toHaveValue("采购申请");
  await page.getByRole("button", { name: "发布当前版本" }).focus();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("status").filter({ hasText: "已发布版本 3" }),
  ).toBeVisible();
  await page.getByLabel("台账名称").fill("回退后继续编辑");
  const stay = page.waitForEvent("dialog");
  await Promise.all([
    stay.then((dialog) => dialog.dismiss()),
    page.getByRole("link", { name: "MIGHT 工作台首页" }).click(),
  ]);
  await expect(page.getByLabel("台账名称")).toHaveValue("回退后继续编辑");
  const leave = page.waitForEvent("dialog");
  await Promise.all([
    leave.then((dialog) => dialog.accept()),
    page.getByRole("link", { name: "MIGHT 工作台首页" }).click(),
  ]);
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("heading", { name: "台账配置" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "台账名称" })).toHaveValue("回退后继续编辑");

  loseNextSaveResponse = true;
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(page.getByRole("alert")).toContainText("操作未确认成功");
  await page.goForward();
  await expect(page.locator(".ledger-row").first()).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("textbox", { name: "台账名称" })).toHaveValue("回退后继续编辑");
  await expect(page.getByRole("button", { name: "新建台账" })).toBeDisabled();
  await page.getByRole("button", { name: "重试原操作" }).click();
  await expect(page.getByRole("status").filter({ hasText: "已保存草稿版本 4" })).toBeVisible();
  expect(versions.map(({ version }) => version)).toEqual([4, 3, 2, 1]);

  for (const [theme, width] of [
    ["light", 1440],
    ["dark", 390],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    const { violations } = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(
      violations.map(
        (value) =>
          `${value.id}: ${value.nodes.map((node) => node.target).join(",")}`,
      ),
    ).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  }
});
