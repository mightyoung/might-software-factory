import { expect, test } from "@playwright/test";
import { exporterGlb, syntheticGlb } from "../core/glbFixture.ts";

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

test("validates local GLB and frees buffers across 50 real browser replacements", async ({ page }) => {
  await page.addInitScript(() => {
    window.meshDraws = 0;
    window.bufferReleases = 0;
    const draw = WebGL2RenderingContext.prototype.drawElements;
    const release = WebGL2RenderingContext.prototype.deleteBuffer;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      window.meshDraws++;
      return draw.apply(this, args);
    };
    WebGL2RenderingContext.prototype.deleteBuffer = function (...args) {
      window.bufferReleases++;
      return release.apply(this, args);
    };
  });
  const extraRequests = [];
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const metrics = () => page.evaluate(() => ({ draws: window.meshDraws, releases: window.bufferReleases }));
  await expect.poll(async () => (await metrics()).draws).toBeGreaterThan(0);
  page.on("request", (request) => extraRequests.push(request.url()));
  const picker = page.getByLabel("选择 GLB 文件");
  const glb = Buffer.from(syntheticGlb());
  const invalid = Buffer.from(syntheticGlb((doc) => { doc.buffers[0].uri = "https://example.com/blocked.bin"; }));
  const before = await metrics();
  for (let i = 0; i < 50; i++) {
    const previous = await metrics();
    const name = `synthetic-${i}.glb`;
    await picker.setInputFiles({ name, mimeType: "model/gltf-binary", buffer: glb });
    await expect(page.getByText(`${name}：4 个三角面，4 个顶点，2 × 2 × 2 米。`)).toBeVisible();
    await expect.poll(async () => (await metrics()).draws).toBeGreaterThan(previous.draws);
  }
  await picker.setInputFiles({ name: "external.glb", mimeType: "model/gltf-binary", buffer: invalid });
  await expect(page.getByRole("alert")).toContainText("不支持外链");
  await expect(page.getByText("synthetic-49.glb：4 个三角面，4 个顶点，2 × 2 × 2 米。")).toBeVisible();
  const back = page.getByRole("button", { name: "返回合成场景" });
  await back.click();
  await expect(back).toBeHidden();
  await expect(picker).toBeFocused();
  await expect.poll(async () => (await metrics()).releases - before.releases).toBeGreaterThanOrEqual(50);
  expect(extraRequests).toEqual([]);
});

test("keyboard face resizing and explicit GLB units work in the isolated editor", async ({ page }) => {
  await page.goto("/");
  const robot = page.getByRole("button", { name: "机械臂 06 · 预警" });
  await robot.focus();
  await page.keyboard.press("Enter");
  const tool = page.getByRole("group", { name: "保持对侧不动地调整尺寸" });
  await expect(tool.getByRole("status")).toHaveText("长 2 · 宽 1.8 · 高 2.2 米。");
  await tool.getByLabel("设备局部面").selectOption("+Y");
  await tool.getByRole("button", { name: "伸出 0.5 米" }).focus();
  await page.keyboard.press("Enter");
  await expect(tool.getByRole("status")).toHaveText("长 2 · 宽 1.8 · 高 2.7 米。");
  await expect(robot).toHaveAttribute("aria-pressed", "true");

  const source = page.getByLabel("模型源单位");
  const picker = page.getByLabel("选择 GLB 文件");
  await picker.setInputFiles({ name: "two-unit.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(syntheticGlb()) });
  await expect(page.getByText("two-unit.glb：4 个三角面，4 个顶点，2 × 2 × 2 米。")).toBeVisible();
  await source.selectOption("mm");
  await expect(page.getByText("two-unit.glb：4 个三角面，4 个顶点，0.002 × 0.002 × 0.002 米。")).toBeVisible();
  await source.selectOption("m");
  await expect(page.getByText("two-unit.glb：4 个三角面，4 个顶点，2 × 2 × 2 米。")).toBeVisible();
});

test("local exporter-shaped GLB honors parent transform without network or texture loads", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const network = [];
  page.on("request", (request) => network.push(request.url()));
  await page.getByLabel("选择 GLB 文件").setInputFiles({
    name: "exporter.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(exporterGlb()),
  });
  await expect(page.getByText("exporter.glb：2 个三角面，4 个顶点，3 × 2 × 1 米。")).toBeVisible();
  expect(network).toEqual([]);
});
