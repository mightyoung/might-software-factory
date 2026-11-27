import { expect, test } from "@playwright/test";

test("authoring saves immutable parametric versions, validates the anchor and restores older selections", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  const panel = page.getByRole("region", { name: "参数化组件库" });
  const save = panel.getByRole("button", { name: "保存组件版本" });
  await save.click();
  await expect(panel.locator("#prefab-status")).toContainText("版本 1");
  const v1 = panel.getByRole("button", { name: /· v1$/ });
  await expect(v1).toHaveAttribute("aria-pressed", "true");
  await panel.getByLabel("锚点 X（米）").fill("3");
  await save.click();
  await expect(panel.locator("#prefab-error")).toContainText("设备边界");
  await expect(panel.getByRole("button", { name: /· v2$/ })).toHaveCount(0);
  await panel.getByLabel("组件长度（米）").fill("8");
  await panel.getByLabel("预览状态").selectOption("alarm");
  await save.click();
  await expect(panel.locator("#prefab-error")).toBeHidden();
  await expect(panel.locator("#prefab-status")).toContainText("版本 2");
  await expect(panel.locator("figcaption")).toContainText("8 × 1 × 1 米 · 告警");
  await expect(panel.locator("circle")).toHaveAttribute("cx", "3");
  await v1.focus();
  await v1.press("Enter");
  await expect(v1).toBeFocused();
  await expect(panel.getByLabel("组件长度（米）")).toHaveValue("4");
  await expect(panel.getByLabel("预览状态")).toHaveValue("normal");
  await expect(panel.locator("circle")).toHaveAttribute("cx", "2");
  await page.reload();
  await expect(panel.locator("#prefab-status")).toHaveText("暂无保存的组件。");
});

test("placing successive versions renders new devices without upgrading existing instances", async ({ page }) => {
  await page.addInitScript(() => {
    window.prefabDraws = 0;
    const draw = WebGL2RenderingContext.prototype.drawElements;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      window.prefabDraws += 1;
      return draw.apply(this, args);
    };
  });
  await page.goto("/");
  await expect(page.locator("#status")).toContainText("已就绪");
  const draws = () => page.evaluate(() => window.prefabDraws);
  await expect.poll(draws).toBeGreaterThan(0);
  const panel = page.getByRole("region", { name: "参数化组件库" });
  const save = panel.getByRole("button", { name: "保存组件版本" });
  const place = panel.getByRole("button", { name: "放入合成场景" });
  await expect(place).toBeDisabled();
  await save.click();
  await panel.getByLabel("放置 X（米）").fill("2");
  await panel.getByLabel("放置 Z（米）").fill("-3");
  const before = await draws();
  await place.click();
  await expect.poll(draws).toBeGreaterThan(before);
  await expect(page.locator("#selection")).toContainText("conveyor-standard · v1 · 长 4 米");
  await expect(panel.locator("#prefab-placement-status")).toContainText("X 2、Z -3 米");
  await expect(page.getByLabel("机位设备").locator('option[value="prefab-instance-1"]')).toHaveText("标准输送带");
  await panel.getByLabel("组件长度（米）").fill("8");
  await save.click();
  await expect(page.locator("#selection")).toContainText("v1 · 长 4 米");
  await place.click();
  await expect(page.locator("#selection")).toContainText("v2 · 长 8 米");
  await page.locator("#devices").getByRole("button", { name: /conveyor-standard · v1$/ }).click();
  await expect(page.locator("#selection")).toContainText("v1 · 长 4 米");
  await expect(page.locator("#resize-tool")).toBeHidden();
});
