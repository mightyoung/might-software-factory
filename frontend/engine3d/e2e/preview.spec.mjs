import { expect, test } from "@playwright/test";
import { syntheticGlb } from "../core/glbFixture.ts";

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

test("top-view pointer snaps an orthogonal wall and keyboard builds only simple zones", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  const editor = page.getByRole("region", { name: "顶视平面绘图" });
  const map = editor.getByRole("img", { name: "顶视图，横轴 X，纵轴 Z" });
  await expect(map).toBeVisible();
  const box = await map.boundingBox();
  await map.click({ position: { x: box.width * 0.5, y: box.height * 0.5 } });
  await map.click({ position: { x: box.width * 0.7, y: box.height * 0.55 }, modifiers: ["Shift"] });
  await expect(editor.locator(".plan-wall")).toHaveAttribute("points", "0,0 40,0");
  await expect(editor.getByRole("status")).toContainText("墙段 1 条");
  await editor.getByLabel("绘制模式").selectOption("zone");
  await editor.getByLabel("吸附网格").selectOption("0.1");
  const append = async (x, z) => {
    await editor.getByLabel("X（米）").fill(String(x));
    const zInput = editor.getByLabel("Z（米）");
    await zInput.fill(String(z));
    await zInput.press("Enter");
  };
  for (const [x, z] of [[0, 0], [4, 4], [0, 4], [3, 0]]) await append(x, z);
  await editor.getByRole("button", { name: "闭合区域" }).click();
  await expect(editor.getByRole("alert")).toContainText("不能交叉");
  await expect(editor.getByRole("status")).toContainText("草稿 4 个点");
  await expect(editor.locator(".plan-zone")).toHaveCount(0);
  await editor.getByRole("button", { name: "清除当前草稿" }).click();
  for (const [x, z] of [[0, 0], [4, 0], [4, 3], [0, 3]]) await append(x, z);
  await expect(editor.getByRole("status")).toContainText("当前草稿约 12 平方米");
  await editor.getByRole("button", { name: "闭合区域" }).click();
  await expect(editor.getByRole("listitem")).toContainText("12 平方米");
  await expect(editor.locator(".plan-zone")).toHaveAttribute("points", "0,0 40,0 40,30 0,30");
  await expect(editor.locator(".plan-wall")).toHaveCount(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test("viewport resize handle commits on release and Escape cancels without resizing", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "传送带 04 · 正常" }).click();
  const size = page.getByRole("group", { name: "保持对侧不动地调整尺寸" }).getByRole("status");
  await expect(size).toHaveText("长 4 · 宽 1 · 高 0.9 米。");
  const handle = page.locator('#resize-gizmo [data-face="+X"]');
  const drag = async (cancel) => {
    await expect(handle).toBeVisible();
    const axis = (await handle.getAttribute("data-axis")).split(",").map(Number);
    const box = await handle.boundingBox();
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + axis[0] * 0.5, y + axis[1] * 0.5, { steps: 4 });
    await expect(size).toHaveText("长 4 · 宽 1 · 高 0.9 米。");
    if (cancel) await page.keyboard.press("Escape");
    await page.mouse.up();
  };
  await drag(true);
  await expect(size).toHaveText("长 4 · 宽 1 · 高 0.9 米。");
  await drag(false);
  await expect.poll(async () => Number((await size.textContent()).match(/长 ([\d.]+)/)?.[1])).toBeGreaterThan(4.3);
  await expect(size).toContainText("宽 1 · 高 0.9 米");
  await expect(page.getByRole("button", { name: "传送带 04 · 正常" })).toHaveAttribute("aria-pressed", "true");
});
