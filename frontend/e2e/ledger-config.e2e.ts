import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

// Test-owned configuration API fixtures exercise real HTTP adapter and browser controls.
test("台账配置保存、比较、恢复、独立复制与显式发布支持 Astryx 键盘操作", async ({
  page,
}) => {
  const editorRequests: string[] = [];
  const permissionReads: string[] = [];
  const configurationReads: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (request.resourceType() === "script" && /\/(?:ledgerConfig|platform)\/[^/]*ConfigPage\.|\/assets\/LedgerConfigPage-[^/]+\.js$/.test(path))
      editorRequests.push(request.url());
    if (request.method() === "GET" && /^\/api\/v1\/admin\/(roles|permissions)$/.test(path))
      permissionReads.push(path);
    if (request.method() === "GET" && path.startsWith("/api/v1/admin/ledger-configurations"))
      configurationReads.push(path);
  });
  const permissions = [
    "ledger.configuration.read",
    "ledger.configuration.manage",
    "ledger.configuration.publish",
    "platform.permission.read",
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
  await page.route("**/api/v1/admin/permissions", (route) => route.fulfill({ json: { items: [] } }));
  await page.route("**/api/v1/admin/roles", (route) => route.fulfill({ json: { roles: [] } }));
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
  const histories = new Map<string, Snapshot[]>([["purchase", versions]]);
  const saveAttempts: { key: string; expectedVersion: number }[] = [];
  await page.route("**/api/v1/admin/ledger-configurations**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const key = path.split("/")[5];
    const current = key ? histories.get(key) ?? [] : [];
    if (route.request().method() === "GET") {
      await route.fulfill({
        json: path.endsWith("/versions")
          ? { versions: current }
          : {
              items: [...histories.values()]
                .filter((history) => history.length > 0)
                .map(([latest]) => ({
                  ...latest,
                  latestVersion: latest!.version,
                })),
            },
      });
      return;
    }
    const body = route.request().postDataJSON();
    expect(key).toBeTruthy();
    if (path.endsWith("/publish")) {
      expect(body.expectedPublishedVersion).toBe(published);
      published = body.version;
      current.forEach((entry) => { entry.publishedVersion = published; });
      await route.fulfill({
        json: { ...current[0], publishedVersion: published },
      });
      return;
    }
    const existing = receipts.get(body.requestId);
    if (existing) {
      expect(body).toEqual(existing.body);
      await route.fulfill({ json: existing.snapshot });
      return;
    }
    if (path.endsWith("/versions")) {
      saveAttempts.push({ key: key!, expectedVersion: body.expectedVersion });
      expect(body.expectedVersion).toBe(current.length);
    }
    const definition = path.endsWith("/restore")
      ? current.find((value) => value.version === body.sourceVersion)!
          .definition
      : body.definition;
    const next = {
      key: key!,
      version: current.length + 1,
      definition,
      publishedVersion: current[0]?.publishedVersion ?? null,
      sourceVersion: body.sourceVersion ?? null,
      savedAt: new Date().toISOString(),
      savedBy: "配置员",
    };
    current.unshift(next);
    histories.set(key!, current);
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
  expect(permissionReads).toEqual([]);
  expect(configurationReads).toEqual([]);
  const settingsPath = new URL(page.url()).pathname.replace("safety/findings", "platform");
  await page.goto(settingsPath + "/permissions");
  await expect(page.getByText("暂无角色。")).toBeVisible();
  expect(editorRequests.length).toBeGreaterThan(0);
  expect(permissionReads).toHaveLength(2);
  expect(configurationReads).toEqual([]);

  await page.goto(settingsPath + "/ledger-configurations");
  await page.getByLabel("台账标识").fill("purchase");
  expect(configurationReads).toEqual(["/api/v1/admin/ledger-configurations"]);
  expect(permissionReads).toHaveLength(2);
  await page.getByLabel("台账名称").fill("采购申请");
  await page.getByRole("button", { name: "添加字段" }).click();
  await page.getByLabel("字段标识").fill("quantity");
  await page.getByLabel("字段名称").fill("数量");
  await page.getByLabel("字段类型").selectOption("number");
  await page.getByRole("checkbox", { name: "列表显示" }).focus();
  await page.keyboard.press("Space");
  await expect(page.getByRole("checkbox", { name: "列表显示" })).toBeChecked();
  await page.getByRole("checkbox", { name: "允许查询" }).click();
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "已保存草稿版本 1" }),
  ).toBeVisible();
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

  const sourceBeforeCopy = structuredClone(versions);
  await page.getByRole("button", { name: "复制版本 1为新台账", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "台账 采购申请（purchase）的版本 1" })).toBeVisible();
  await expect(page.getByLabel("台账标识")).toHaveValue("");
  await expect(page.getByLabel("台账名称")).toHaveValue("采购申请副本");
  await expect(page.getByRole("region", { name: "版本历史" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "发布当前版本" })).toBeDisabled();
  await expect(page.getByRole("checkbox", { name: "列表显示" })).toBeChecked();
  await expect(page.getByRole("checkbox", { name: "允许查询" })).toBeChecked();
  await page.getByLabel("台账标识").fill("purchase_copy");
  await page.getByLabel("台账名称").fill("独立采购台账");
  await page.getByLabel("字段名称").fill("独立数量");
  loseNextSaveResponse = true;
  await page.getByRole("button", { name: "保存草稿" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert")).toContainText("操作未确认成功");
  await expect(page.getByLabel("台账标识")).toBeDisabled();
  await page.getByRole("button", { name: "重试原操作" }).click();
  await expect(page.getByRole("status").filter({ hasText: "已保存草稿版本 1" })).toBeVisible();
  expect(saveAttempts.at(-1)).toEqual({ key: "purchase_copy", expectedVersion: 0 });
  expect(histories.get("purchase_copy")).toHaveLength(1);
  expect(histories.get("purchase_copy")![0]).toMatchObject({
    key: "purchase_copy",
    version: 1,
    publishedVersion: null,
    definition: {
      name: "独立采购台账",
      fields: [{ key: "quantity", label: "独立数量", type: "number", required: false }],
      listFields: ["quantity"],
      queryFields: ["quantity"],
    },
  });
  expect(versions).toEqual(sourceBeforeCopy);
  await page.getByRole("navigation", { name: "已配置台账" })
    .getByRole("button", { name: "回退后继续编辑 最新 4 · 已发布 3" }).click();
  await expect(page.getByLabel("台账标识")).toHaveValue("purchase");
  await expect(page.getByLabel("字段名称")).toHaveValue("数量");
  await expect(page.getByRole("region", { name: "版本历史" }).getByRole("listitem")).toHaveCount(4);
  await page.getByRole("button", { name: "复制版本 1为新台账", exact: true }).click();
  await page.getByLabel("台账标识").fill("purchase_copy");
  const savesBeforeCollision = saveAttempts.length;
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(page.getByRole("alert")).toContainText("新台账标识已存在");
  await expect(page.getByLabel("台账名称")).toHaveValue("采购申请副本");
  expect(saveAttempts).toHaveLength(savesBeforeCollision);
  expect(versions).toEqual(sourceBeforeCopy);
  expect(histories.get("purchase_copy")).toHaveLength(1);
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
