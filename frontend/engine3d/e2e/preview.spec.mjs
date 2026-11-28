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

  const conveyor = page.getByRole("button", { name: "传送带 04 · 正常" });
  await conveyor.click();
  await expect(conveyor).toHaveAttribute("aria-pressed", "true");
  const beforeRebuild = await getDraws();
  await page.getByLabel("长度（米）").selectOption("8");
  await expect(page.getByRole("status").filter({ hasText: "当前长度：8 米" })).toBeVisible();
  await expect(conveyor).toHaveAttribute("aria-pressed", "true");
  await expect.poll(getDraws).toBeGreaterThan(beforeRebuild);

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

test("resizing to a narrow viewport refits the selected device without changing the vertical lens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.addInitScript(() => {
    const gl = WebGL2RenderingContext.prototype;
    const draw = gl.drawArrays;
    window.focusMatrices = {};
    gl.drawArrays = function (...args) {
      if (args[0] === this.LINES && args[2] === 132) {
        // The identity-transform GridHelper uses these matrices in its active vertex shader.
        const program = this.getParameter(this.CURRENT_PROGRAM);
        const read = (name) => {
          const location = this.getUniformLocation(program, name);
          return location ? Array.from(this.getUniform(program, location)) : undefined;
        };
        window.focusMatrices = { modelViewMatrix: read("modelViewMatrix"), projectionMatrix: read("projectionMatrix") };
      }
      return draw.apply(this, args);
    };
  });
  await page.goto("/");
  const read = () => page.evaluate(() => window.focusMatrices);
  await expect.poll(async () => (await read()).modelViewMatrix?.[14]).toBeDefined();
  const overviewDepth = (await read()).modelViewMatrix[14];
  const press = page.getByRole("button", { name: "成型机 02 · 预警" });
  await press.click();
  await expect.poll(async () => (await read()).modelViewMatrix[14]).not.toBe(overviewDepth);
  const normal = await read();
  await page.locator("#viewport").evaluate((viewport) => { viewport.style.width = "48px"; });
  await expect.poll(async () => (await read()).projectionMatrix[0]).toBeGreaterThan(normal.projectionMatrix[0] * 2);
  const narrow = await read();
  expect(narrow.projectionMatrix[5]).toBeCloseTo(normal.projectionMatrix[5], 6);
  expect(narrow.modelViewMatrix[14]).toBeLessThan(normal.modelViewMatrix[14]);
  await expect(press).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "恢复全景" }).click();
  await expect.poll(async () => (await read()).modelViewMatrix[14]).toBeCloseTo(overviewDepth, 6);
  await expect(press).toHaveAttribute("aria-pressed", "false");
});
