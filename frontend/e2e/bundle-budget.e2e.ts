import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { gzipSync } from "node:zlib";
import { expect, test, type Page } from "@playwright/test";

async function loadedAssets(page: Page) {
  return page.evaluate(() => {
    const local = performance.getEntriesByType("resource").flatMap((entry) => {
      const url = new URL(entry.name);
      return url.origin === location.origin && /^\/assets\/[^/]+\.(js|css)$/.test(url.pathname) ? [url.pathname] : [];
    });
    return [...new Set(local)].sort();
  });
}

function gzipTotals(assets: Iterable<string>) {
  const totals = { js: 0, css: 0 };
  for (const file of assets) {
    const format = file.endsWith(".js") ? "js" : "css";
    totals[format] += gzipSync(readFileSync(resolve("dist", `.${file}`))).length;
  }
  return totals;
}

test("HTTP 冷启动实际加载的资源在首屏预算内，且不预加载台账或 Office", async ({ page }) => {
  test.skip(process.env.E2E_PREVIEW !== "http", "只测生产构建，不用开发资源代替");
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible();
  await page.waitForLoadState("networkidle");
  const assets = await loadedAssets(page);
  expect(assets.some((file) => file.endsWith(".js"))).toBe(true);
  expect(assets.some((file) => file.endsWith(".css"))).toBe(true);
  expect(assets.some((file) => /OfficeDialog|PublishedLedgersPage|LedgerConfigPage|PermissionConfigPage/.test(file))).toBe(false);
  const totals = gzipTotals(assets);
  console.log(`Cold HTTP workbench loaded ${assets.length} JS/CSS assets: ${totals.js} B JS, ${totals.css} B CSS (gzip)`);
  expect(totals.js).toBeLessThanOrEqual(235 * 1024);
  expect(totals.css).toBeLessThanOrEqual(56 * 1024);
});

type ManifestChunk = { file: string; imports?: string[]; css?: string[] };
function manifestClosure(manifest: Record<string, ManifestChunk>, key: string, visited = new Set<string>()): string[] {
  if (visited.has(key)) return [];
  visited.add(key);
  const chunk = manifest[key];
  if (!chunk) throw new Error(`Missing manifest chunk: ${key}`);
  return [chunk.file, ...(chunk.css ?? []), ...(chunk.imports ?? []).flatMap((child) =>
    manifestClosure(manifest, child, visited),
  )].map((path) => path.startsWith("/") ? path : `/${path}`);
}

test("HTTP 已登录默认页和业务台账深链接分别满足冷启动预算", async ({ browser, page }, testInfo) => {
  test.skip(process.env.E2E_PREVIEW !== "http", "只测生产构建，不用开发资源代替");
  const baseURL = testInfo.project.use.baseURL;
  await page.goto("/login");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号", { exact: true }).fill("demo");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: "隐患台账" })).toBeVisible();
  const tenantPath = new URL(page.url()).pathname.split("/").slice(0, 3).join("/");
  expect(tenantPath).toMatch(/^\/t\/[^/]+$/);
  const storageState = await page.context().storageState();

  // Copy only session state, not browser cache. Each route starts in an isolated context.
  const landingContext = await browser.newContext({ baseURL, storageState });
  const ledgerContext = await browser.newContext({ baseURL, storageState });
  try {
    const landing = await landingContext.newPage();
    await landing.goto("/");
    await expect(landing).toHaveURL(new RegExp(`${tenantPath}/safety/findings$`));
    await expect(landing.locator(".ledger-row").first()).toBeVisible();
    await landing.waitForLoadState("networkidle");
    const landingAssets = await loadedAssets(landing);
    const landingTotals = gzipTotals(landingAssets);
    expect(landingTotals.js).toBeGreaterThan(0);
    expect(landingTotals.js).toBeLessThanOrEqual(235 * 1024);
    expect(landingTotals.css).toBeLessThanOrEqual(56 * 1024);
    expect(landingAssets.some((file) => /OfficeDialog|PublishedLedgersPage|LedgerConfigPage|PermissionConfigPage/.test(file))).toBe(false);

    const deniedReads: string[] = [];
    landing.on("request", request => {
      const pathname = new URL(request.url()).pathname;
      if (pathname === "/api/v1/ledgers" || pathname.includes("PublishedLedgersPage-")) deniedReads.push(pathname);
    });
    await landing.goto(`${tenantPath}/platform/ledgers`);
    await expect(landing.getByRole("heading", { name: "无权访问" })).toBeVisible();
    await landing.waitForLoadState("networkidle");
    expect(deniedReads).toEqual([]);

    const ledger = await ledgerContext.newPage();
    const permissions = ["ledger.directory.open"];
    const authorization = { tenantAuthEpoch: 1, memberAuthEpoch: 1, applicationVersion: 1 };
    await ledger.route("**/api/v1/session/bootstrap", async (route) => {
      const response = await route.fetch();
      if (!response.ok()) return route.fulfill({ response });
      const body = await response.json();
      body.data.permissions = permissions;
      body.data.applications = [{ id: "platform", name: "系统设置", entryPath: "platform/ledgers" }];
      return route.fulfill({ response, json: body });
    });
    await ledger.route("**/api/v1/applications/platform/menu", (route) => route.fulfill({
      json: { data: { schemaVersion: 1, applicationId: "platform", title: "系统设置", permissions, authorization, nodes: [
        { id: "ledgers", label: "业务台账", path: "platform/ledgers", permission: permissions[0] },
      ] } },
    }));
    await ledger.route("**/api/v1/ledgers", (route) => route.fulfill({ json: { items: [] } }));
    await ledger.goto(`${tenantPath}/platform/ledgers`);
    await expect(ledger.getByRole("heading", { name: "业务台账" })).toBeVisible();
    await expect(ledger.getByText("暂无可访问的已发布台账。")).toBeVisible();
    await ledger.waitForLoadState("networkidle");
    const ledgerAssets = new Set(await loadedAssets(ledger));
    const ledgerTotals = gzipTotals(ledgerAssets);
    const manifest = JSON.parse(readFileSync(resolve("dist/.vite/manifest.json"), "utf8")) as Record<string, ManifestChunk>;
    const entry = new Set(manifestClosure(manifest, "index.html"));
    const route = new Set(manifestClosure(manifest, "src/features/publishedLedgers/PublishedLedgersPage.tsx"));
    const additional = [...ledgerAssets].filter((file) => !landingAssets.includes(file));
    const expectedExtra = [...route].filter((file) => !entry.has(file));
    expect(expectedExtra.length).toBeGreaterThan(0);
    for (const asset of expectedExtra) {
      expect(ledgerAssets.has(asset), `Deep link requested ${asset}`).toBe(true);
      expect(landingAssets.includes(asset), `Landing did not preload ${asset}`).toBe(false);
    }
    const extraTotals = gzipTotals(additional);
    const expectedTotals = gzipTotals(expectedExtra);
    expect(extraTotals.js).toBeGreaterThanOrEqual(expectedTotals.js);
    expect(extraTotals.js).toBeLessThanOrEqual(6 * 1024);
    expect(extraTotals.css).toBeLessThanOrEqual(1.5 * 1024);
    expect(ledgerTotals.js).toBeLessThanOrEqual((235 + 6) * 1024);
    expect([...ledgerAssets].some((file) => /OfficeDialog|LedgerConfigPage|PermissionConfigPage/.test(file))).toBe(false);
    console.log(`Authenticated cold landing: ${landingTotals.js} B JS, ${landingTotals.css} B CSS; ledger deep link: ${ledgerTotals.js} B JS, ${ledgerTotals.css} B CSS; extra ${extraTotals.js} B JS, ${extraTotals.css} B CSS (gzip)`);
  } finally {
    await Promise.all([landingContext.close(), ledgerContext.close()]);
  }
});
