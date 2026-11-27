import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

test("已发布台账提供配置字段查询和草稿增改删", async ({ page }) => {
  const permissions = ["ledger.directory.open"];
  await page.route("**/api/v1/session/bootstrap", async (route) => {
    const response = await route.fetch();
    if (!response.ok()) return route.fulfill({ response });
    const body = await response.json();
    body.data.permissions = permissions;
    body.data.applications = [{
      id: "platform",
      name: "系统设置",
      entryPath: "platform/ledgers",
    }];
    await route.fulfill({ response, json: body });
  });
  await page.route("**/api/v1/applications/platform/menu", (route) =>
    route.fulfill({
      json: {
        data: {
          schemaVersion: 1,
          applicationId: "platform",
          title: "系统设置",
          permissions,
          nodes: [
            {
              id: "ledgers",
              label: "业务台账",
              path: "platform/ledgers",
              permission: permissions[0],
            },
          ],
          authorization: {
            tenantAuthEpoch: 1,
            memberAuthEpoch: 1,
            applicationVersion: 1,
          },
        },
      },
    }),
  );
  const definition = {
    name: "采购申请",
    fields: [
      { key: "quantity", label: "采购数量", type: "number", required: true },
      { key: "confirmed", label: "已确认", type: "boolean", required: false },
    ],
    listFields: ["quantity", "confirmed"],
    queryFields: ["confirmed", "quantity"],
  };
  const capabilities = { read: true, create: true, update: true, delete: true };
  const item = {
    key: "purchase",
    definitionVersion: 2,
    definition,
    capabilities,
    organizations: [{ id: "org", name: "生产部" }],
  };
  let record: null | {
    id: string;
    definitionVersion: number;
    organizationId: string;
    values: Record<string, unknown>;
    version: number;
    createdAt: string;
    updatedAt: string;
  } = null;
  let creates = 0;
  let updates = 0;
  let lostTrackingCreates = 0;
  let loseTrackingResponse = false;
  let deletes = 0;
  let refuseDirectory = false;
  let firstCreate: unknown;
  const queries: unknown[] = [];
  await page.route("**/api/v1/ledgers**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/ledgers")
      return refuseDirectory
        ? route.fulfill({ status: 403, json: { code: "FORBIDDEN", message: "Directory permission revoked" } })
        : route.fulfill({ json: { items: [item] } });
    if (path.endsWith("/query")) {
      queries.push(route.request().postDataJSON());
      return route.fulfill({
        json: {
          records: record ? [record] : [],
          total: record ? 1 : 0,
          page: 1,
          size: 20,
          hasMore: false,
        },
      });
    }
    if (route.request().method() === "GET")
      return route.fulfill({ json: { record, definition, capabilities } });
    const body = route.request().postDataJSON();
    if (path.endsWith("/delete")) {
      expect(path).toBe("/api/v1/ledgers/purchase/records/row-1/delete");
      expect(body).toEqual({ requestId: expect.any(String), expectedVersion: 2 });
      deletes += 1;
      record = null;
      return route.fulfill({ json: {} });
    }
    if (route.request().method() === "PUT") {
      updates += 1;
      expect(body.expectedVersion).toBe(1);
      record = { ...record!, values: body.values, version: 2 };
    } else {
      if (loseTrackingResponse) {
        lostTrackingCreates += 1;
        expect(body.values.quantity).toBe(9);
        record = {
          id: "row-2",
          definitionVersion: 2,
          organizationId: "org",
          values: body.values,
          version: 1,
          createdAt: "2026-11-20T00:00:00Z",
          updatedAt: "2026-11-20T00:00:00Z",
        };
        return route.abort();
      }
      if (firstCreate) {
        expect(body).toEqual(firstCreate);
        return route.fulfill({ json: record });
      }
      creates += 1;
      firstCreate = body;
      expect(body.definitionVersion).toBe(2);
      expect(body.organizationId).toBe("org");
      record = {
        id: "row-1",
        definitionVersion: 2,
        organizationId: "org",
        values: body.values,
        version: 1,
        createdAt: "2026-11-20T00:00:00Z",
        updatedAt: "2026-11-20T00:00:00Z",
      };
      return route.abort();
    }
    await route.fulfill({ json: record });
  });
  await page.goto("/login");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号", { exact: true }).fill("mock-person");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("link", { name: "业务台账", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "采购申请", exact: true }).click();
  await expect(page.getByText("共 0 条记录")).toBeVisible();
  const query = page.locator(".published-query");
  await expect(query.locator("label").first().getByRole("combobox", { name: "已确认" })).toBeVisible();
  await expect(query.locator("label").nth(1).getByRole("spinbutton", { name: "采购数量" })).toBeVisible();
  await expect(page.getByRole("columnheader")).toHaveText(["采购数量", "已确认", "操作"]);
  await query.getByRole("combobox", { name: "已确认" }).selectOption("false");
  await query.getByRole("spinbutton", { name: "采购数量" }).fill("5");
  await query.getByRole("button", { name: "查询", exact: true }).click();
  await expect.poll(() => queries.at(-1)).toEqual({
    page: 1, size: 20,
    filters: [
      { field: "quantity", operator: "eq", value: 5 },
      { field: "confirmed", operator: "eq", value: false },
    ],
  });
  await page.getByRole("button", { name: "新增记录", exact: true }).click();
  let editor = page.getByRole("region", { name: "新增记录", exact: true });
  await editor.getByLabel("采购数量（必填）").fill("5");
  await editor.getByRole("checkbox", { name: "已确认" }).focus();
  await page.keyboard.press("Space");
  await editor.getByRole("button", { name: "保存记录" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert")).toContainText("操作未确认成功");
  await expect(editor.getByLabel("采购数量（必填）")).toBeDisabled();
  await page.evaluate(() => {
    window.history.pushState(null, "", "/t/demo/not-registered");
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  await expect(editor).toBeHidden();
  await page.goBack();
  await expect(editor.getByLabel("采购数量（必填）")).toHaveValue("5");
  await page.getByRole("button", { name: "重试原操作" }).click();
  await expect(page.getByText("共 1 条记录")).toBeVisible();
  expect(creates).toBe(1);
  expect(record!.values).toEqual({ quantity: 5, confirmed: true });
  await page.getByRole("button", { name: "查看记录" }).click();
  await page.getByRole("button", { name: "编辑草稿" }).click();
  editor = page.getByRole("region", { name: "记录详情", exact: true });
  const quantity = editor.getByLabel("采购数量（必填）");
  await quantity.fill("6");
  const nativeSwitchDialogs: string[] = [];
  const nativeSwitch = async (dialog: import("@playwright/test").Dialog) => {
    nativeSwitchDialogs.push(dialog.type());
    await dialog.dismiss();
  };
  page.on("dialog", nativeSwitch);
  const closeEditor = editor.getByRole("button", { name: "关闭记录" });
  await closeEditor.focus();
  await page.keyboard.press("Enter");
  const discard = page.getByRole("alertdialog", { name: "放弃未保存的记录修改？" });
  await expect(discard.getByRole("button", { name: "继续编辑" })).toBeFocused();
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(violations.map(({ id }) => id)).toEqual([]);
  await page.keyboard.press("Escape");
  await expect(discard).toHaveCount(0);
  await expect(closeEditor).toBeFocused();
  await expect(quantity).toHaveValue("6");
  expect(updates).toBe(0);
  expect(nativeSwitchDialogs).toEqual([]);
  page.off("dialog", nativeSwitch);
  await editor.getByRole("button", { name: "保存记录" }).click();
  await expect(
    page.getByRole("cell", { name: "6", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "查看记录" }).click();
  const remove = page.getByRole("button", { name: "删除草稿" });
  const confirmation = page.getByRole("alertdialog", { name: "删除这条草稿记录？" });
  const cancelDelete = confirmation.getByRole("button", { name: "取消" });
  const confirmDelete = confirmation.getByRole("button", { name: "确认删除" });
  const nativeDialogs: string[] = [];
  page.on("dialog", async dialog => {
    nativeDialogs.push(dialog.type());
    await dialog.dismiss();
  });
  for (const [theme, width] of [
    ["light", 1440],
    ["dark", 390],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("combobox", { name: "主题" }).selectOption(theme);
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
          .analyze()
      ).violations.map((v) => v.id),
    ).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await remove.focus();
    await page.keyboard.press("Enter");
    await expect(cancelDelete).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(confirmDelete).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(cancelDelete).toBeFocused();
    const { violations } = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
    expect(violations.map(({ id }) => id)).toEqual([]);
    await page.keyboard.press("Escape");
    await expect(confirmation).toHaveCount(0);
    await expect(remove).toBeFocused();
    expect(deletes).toBe(0);
  }
  await page.keyboard.press("Enter");
  await confirmDelete.click();
  await expect(page.getByText("共 0 条记录")).toBeVisible();
  expect(deletes).toBe(1);

  loseTrackingResponse = true;
  await page.getByRole("button", { name: "新增记录" }).click();
  const newDraft = page.getByRole("region", { name: "新增记录", exact: true });
  await newDraft.getByLabel("采购数量（必填）").fill("9");
  await newDraft.getByRole("button", { name: "保存记录" }).click();
  await expect(page.getByRole("alert")).toContainText("操作未确认成功");
  expect(lostTrackingCreates).toBe(1);
  const endTracking = page.getByRole("button", { name: "结束追踪", exact: true });
  const stop = page.getByRole("alertdialog", { name: "结束本地追踪？" });
  await endTracking.focus();
  await page.keyboard.press("Enter");
  await expect(stop.getByRole("button", { name: "继续追踪" })).toBeFocused();
  await expect(stop).toContainText("不会撤销");
  await page.keyboard.press("Escape");
  await expect(stop).toHaveCount(0);
  await expect(endTracking).toBeFocused();
  await expect(page.getByRole("button", { name: "重试原操作" })).toBeVisible();
  await page.getByRole("button", { name: "只读刷新记录" }).click();
  await expect(page.getByText("共 1 条记录")).toBeVisible();
  await endTracking.click();
  await stop.getByRole("button", { name: "确认结束追踪" }).click();
  await expect(newDraft).toHaveCount(0);
  await expect(page.getByRole("button", { name: "重试原操作" })).toHaveCount(0);
  await expect(page.getByText("共 1 条记录")).toBeVisible();
  expect(record?.id).toBe("row-2");
  expect(lostTrackingCreates).toBe(1);
  expect(deletes).toBe(1);
  expect(nativeDialogs).toEqual([]);

  refuseDirectory = true;
  await page.getByRole("button", { name: "刷新台账", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("当前授权不允许此操作");
  await expect(page.getByRole("button", { name: "采购申请", exact: true })).toHaveCount(0);
  await expect(page.getByText("共 1 条记录")).toHaveCount(0);
  expect(creates).toBe(1);
  expect(updates).toBe(1);
});
