import { expect, test } from "@playwright/test";

test("HTTP tenant switch clears the old role draft and foreign deep links cannot read its catalog", async ({ page }) => {
  const north = { id: "fixture-north", label: "北区角色" };
  const south = { id: "fixture-south", label: "南区角色" };
  let current: typeof north | null = null;
  const grants = ["platform.permission.read", "platform.permission.manage"];
  const reads: string[] = [];
  const unexpected: string[] = [];
  const permissionPath = (tenant: typeof north) => `/t/${tenant.id}/platform/permissions`;

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path === "/api/v1/auth/csrf" && method === "GET") {
      return route.fulfill({ status: 204, headers: { "Set-Cookie": "XSRF-TOKEN=fixture; Path=/; SameSite=Lax" } });
    }
    if (path === "/api/v1/auth/login" && method === "POST") {
      const body = request.postDataJSON();
      current = [north, south].find((tenant) => tenant.id === body.tenantCode) ?? null;
      return route.fulfill({ status: current ? 204 : 401 });
    }
    if (path === "/api/v1/auth/logout" && method === "POST") {
      current = null;
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/v1/session/bootstrap" && method === "GET") {
      if (!current) return route.fulfill({ status: 401, json: { code: "AUTH_REQUIRED" } });
      return route.fulfill({ json: { data: {
        schemaVersion: 1,
        identity: {
          tenantId: current.id, tenantName: current.id, actorId: `${current.id}-actor`,
          accountId: "shared-fixture-account", memberId: `${current.id}-member`, displayName: `${current.label}会话用户`,
        },
        applications: [{ id: "platform", name: "系统设置", entryPath: "platform/permissions" }],
        permissions: grants,
      } } });
    }
    if (current && method === "GET") {
      if (path === "/api/v1/applications/platform/menu") {
        return route.fulfill({ json: { data: {
          schemaVersion: 1, applicationId: "platform", title: "系统设置", permissions: grants,
          authorization: { tenantAuthEpoch: 1, memberAuthEpoch: 1, applicationVersion: 1 },
          nodes: [{ id: "permissions", label: "权限配置", path: "platform/permissions", permission: grants[0] }],
        } } });
      }
      if (path === "/api/v1/admin/permissions" || path === "/api/v1/admin/roles") {
        reads.push(`${current.id} ${path}`);
        return route.fulfill(path.endsWith("permissions") ? { json: { items: [
          { code: "risk.read", application: "safety", label: "查看风险管控" },
        ] } } : { json: { roles: [
          { id: "same-role-id", code: "reviewer", name: current.label, active: true, memberCount: 1, permissionCodes: [], version: "1" },
        ] } });
      }
    }
    unexpected.push(`${method} ${path}`);
    return route.fulfill({ status: 403, json: { code: "FORBIDDEN" } });
  });

  async function signIn(tenant: typeof north) {
    await page.getByLabel("租户编码").fill(tenant.id);
    await page.getByLabel("账号", { exact: true }).fill("fixture");
    await page.getByLabel("密码", { exact: true }).fill("fixture");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page).toHaveURL((url) => url.pathname === permissionPath(tenant));
    await expect(page.getByRole("heading", { name: tenant.label })).toBeVisible();
  }

  async function denyForeignLink(tenant: typeof north) {
    const priorReads = [...reads];
    await page.goto(permissionPath(tenant));
    await expect(page.getByRole("heading", { name: "无权访问" })).toBeVisible();
    await expect(page).toHaveURL((url) => url.pathname === permissionPath(tenant));
    await page.waitForLoadState("networkidle");
    expect(reads).toEqual(priorReads);
    expect(unexpected).toEqual([]);
  }

  await page.goto("/login");
  await signIn(north);
  const checkbox = page.getByRole("checkbox", { name: "查看风险管控" });
  await checkbox.press("Space");
  await expect(checkbox).toBeChecked();
  await expect(page.getByRole("button", { name: "保存", exact: true })).not.toHaveAttribute("aria-disabled", "true");
  await page.getByRole("button", { name: `${north.label}会话用户`, exact: true }).click();
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();

  await signIn(south);
  await expect(checkbox).not.toBeChecked();
  await expect(page.getByRole("heading", { name: north.label })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "保存", exact: true })).toHaveAttribute("aria-disabled", "true");
  expect(reads.filter((read) => read.startsWith(north.id))).toHaveLength(2);
  expect(reads.filter((read) => read.startsWith(south.id))).toHaveLength(2);
  await denyForeignLink(north);
  await page.goto(permissionPath(south));
  await expect(page.getByRole("heading", { name: south.label })).toBeVisible();
  await expect(checkbox).not.toBeChecked();
  expect(unexpected).toEqual([]);
});
