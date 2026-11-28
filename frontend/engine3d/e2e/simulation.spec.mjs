import { expect, test } from "@playwright/test";

for (const width of [390, 1440]) test(`synthetic DES runs in an isolated Worker at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await page.goto("/");
  const panel = page.getByRole("region", { name: "串行产线仿真" });
  const batch = panel.getByRole("spinbutton", { name: "仿真批量（件）" });
  await batch.fill("4");
  const firstWorker = page.waitForEvent("worker");
  await panel.getByRole("button", { name: "运行仿真" }).click();
  const worker = await firstWorker;
  const workerUrl = new URL(worker.url());
  expect(workerUrl.origin).toBe(new URL(page.url()).origin);
  expect(workerUrl.pathname).toMatch(/\/(?:assets\/preview\.worker-|sim\/preview\.worker\.ts)/);
  await expect(panel.getByRole("status")).toHaveText("仿真完成。");
  await expect(panel.locator("#sim-completed")).toHaveText("4 件");
  await expect(panel.locator("#sim-makespan")).toHaveText("15 秒");
  await expect(panel.locator("#sim-bottleneck")).toHaveText("加工 · 3 秒/件");
  await expect(panel.locator("#sim-theoretical")).toHaveText("1,200 件/小时");
  await expect(panel.locator("#sim-effective")).toHaveText("960 件/小时");
  await batch.fill("1");
  await batch.press("Enter");
  await expect(panel.locator("#sim-completed")).toHaveText("1 件");
  await expect(panel.locator("#sim-makespan")).toHaveText("6 秒");
});
