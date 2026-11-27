import { expect, test } from "@playwright/test";

test("draws devices, preserves selection during WebGL loss and draws again after restore", async ({ page }) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.addInitScript(() => {
    window.draws = 0;
    const draw = WebGL2RenderingContext.prototype.drawElements;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      window.draws += 1;
      return draw.apply(this, args);
    };
  });
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const getDraws = () => page.evaluate(() => window.draws);
  await expect.poll(getDraws).toBeGreaterThan(0);
  const initial = await getDraws();
  const pump = page.getByRole("button", { name: "循环泵 01 · 正常" });
  await pump.focus();
  await page.keyboard.press("Enter");
  await expect(pump).toHaveAttribute("aria-pressed", "true");
  await expect.poll(getDraws).toBeGreaterThan(initial);

  await page.evaluate(() => {
    const gl = document.querySelector("canvas").getContext("webgl2");
    window.loss = gl.getExtension("WEBGL_lose_context");
    if (!window.loss) throw new Error("Chrome has no WEBGL_lose_context test extension");
    window.loss.loseContext();
  });
  await expect(page.getByRole("status").filter({ hasText: "三维视口暂时中断" })).toBeVisible();
  const duringLoss = await getDraws();
  const press = page.getByRole("button", { name: "成型机 02 · 预警" });
  await press.click();
  await expect(press).toHaveAttribute("aria-pressed", "true");
  expect(await getDraws()).toBe(duringLoss);

  await page.evaluate(() => window.loss.restoreContext());
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  await expect.poll(getDraws).toBeGreaterThan(duringLoss);
  await expect(press).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "恢复全景" }).click();
  await expect(press).toHaveAttribute("aria-pressed", "false");
  expect(errors).toEqual([]);
});

test("without WebGL, the keyboard-operable device list still works on narrow viewports", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.addInitScript(() => {
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type, ...args) {
      return type.startsWith("webgl") ? null : getContext.call(this, type, ...args);
    };
  });
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "此设备无法使用 WebGL 2" })).toBeVisible();
  await expect(page.locator("canvas")).toBeHidden();
  const fan = page.getByRole("button", { name: "排风装置 03 · 告警" });
  await fan.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status").filter({ hasText: "已选：排风装置 03 · 告警" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
