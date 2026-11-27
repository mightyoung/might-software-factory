import { expect, test } from "@playwright/test";

test("real software WebGL counts 500 draws vs one and releases tracked geometry on every replacement", async ({ page }) => {
  await page.goto("/probe.html");
  const run = page.getByRole("button", { name: "对比绘制" });
  for (let i = 0; i < 2; i++) {
    await run.click();
    await expect(page.getByRole("status")).toHaveText("对比完成。");
    await expect(page.locator("#probe-draws-individual")).toHaveText("500");
    await expect(page.locator("#probe-draws-instanced")).toHaveText("1");
    await expect(page.locator("#probe-triangles")).toHaveText("6000 / 6000");
    await expect(page.locator("#probe-geometry")).toHaveText("0 / 0");
    await expect(page.locator("#probe-disposals")).toHaveText("2 / 2 / 1");
  }
});
