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
    queryFields: ["quantity"],
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
  await page.route("**/api/v1/ledgers**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/ledgers")
      return route.fulfill({ json: { items: [item] } });
    if (path.endsWith("/query"))
      return route.fulfill({
        json: {
          records: record ? [record] : [],
          total: record ? 1 : 0,
          page: 1,
          size: 20,
          hasMore: false,
        },
      });
    if (route.request().method() === "GET")
      return route.fulfill({ json: { record, definition, capabilities } });
    const body = route.request().postDataJSON();
    if (path.endsWith("/delete")) {
      expect(body.expectedVersion).toBe(2);
      record = null;
      return route.fulfill({ json: {} });
    }
    if (route.request().method() === "PUT") {
      expect(body.expectedVersion).toBe(1);
      record = { ...record!, values: body.values, version: 2 };
    } else {
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
  await page.getByRole("button", { name: "新增记录", exact: true }).click();
  let editor = page.getByRole("region", { name: "新增记录", exact: true });
  await editor.getByLabel("采购数量（必填）").fill("5");
  await editor.getByRole("checkbox", { name: "已确认" }).focus();
  await page.keyboard.press("Space");
  await editor.getByRole("button", { name: "保存记录" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByText("共 1 条记录")).toBeVisible();
  expect(record!.values).toEqual({ quantity: 5, confirmed: true });
  await page.getByRole("button", { name: "查看记录" }).click();
  await page.getByRole("button", { name: "编辑草稿" }).click();
  editor = page.getByRole("region", { name: "记录详情", exact: true });
  await editor.getByLabel("采购数量（必填）").fill("6");
  await editor.getByRole("button", { name: "保存记录" }).click();
  await expect(
    page.getByRole("cell", { name: "6", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "查看记录" }).click();
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
  }
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "删除草稿" }).click();
  await expect(page.getByText("共 0 条记录")).toBeVisible();
});
