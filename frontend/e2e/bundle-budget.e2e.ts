import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { gzipSync } from "node:zlib";
import { expect, test } from "@playwright/test";

test("HTTP 冷启动实际加载的资源在首屏预算内，且不预加载台账或 Office", async ({ page }) => {
  test.skip(process.env.E2E_PREVIEW !== "http", "只测生产构建，不用开发资源代替");
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible();
  await page.waitForLoadState("networkidle");
  const assets = await page.evaluate(() => {
    const local = performance.getEntriesByType("resource").flatMap((entry) => {
      const url = new URL(entry.name);
      return url.origin === location.origin && /^\/assets\/[^/]+\.(js|css)$/.test(url.pathname) ? [url.pathname] : [];
    });
    return [...new Set(local)].sort();
  });
  expect(assets.some((file) => file.endsWith(".js"))).toBe(true);
  expect(assets.some((file) => file.endsWith(".css"))).toBe(true);
  expect(assets.some((file) => /OfficeDialog|PublishedLedgersPage|LedgerConfigPage|PermissionConfigPage/.test(file))).toBe(false);
  const totals = { js: 0, css: 0 };
  for (const file of assets) {
    const format = file.endsWith(".js") ? "js" : "css";
    totals[format] += gzipSync(readFileSync(resolve("dist", `.${file}`))).length;
  }
  console.log(`Cold HTTP workbench loaded ${assets.length} JS/CSS assets: ${totals.js} B JS, ${totals.css} B CSS (gzip)`);
  expect(totals.js).toBeLessThanOrEqual(235 * 1024);
  expect(totals.css).toBeLessThanOrEqual(56 * 1024);
});
