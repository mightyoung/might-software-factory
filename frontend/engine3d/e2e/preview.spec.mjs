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
    window.modelDraws = 0;
    window.bufferReleases = 0;
    const draw = WebGL2RenderingContext.prototype.drawElements;
    const release = WebGL2RenderingContext.prototype.deleteBuffer;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      window.meshDraws++;
      if (args[0] === this.TRIANGLES && args[1] === 12) window.modelDraws++;
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
  const metrics = () => page.evaluate(() => ({ draws: window.meshDraws, models: window.modelDraws, releases: window.bufferReleases }));
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
    await expect.poll(async () => (await metrics()).models).toBeGreaterThan(previous.models);
  }
  await picker.setInputFiles({ name: "external.glb", mimeType: "model/gltf-binary", buffer: invalid });
  await expect(page.getByRole("alert")).toContainText("不支持外链");
  await expect(page.getByText("synthetic-49.glb：4 个三角面，4 个顶点，2 × 2 × 2 米。")).toBeVisible();
  const back = page.getByRole("button", { name: "返回合成场景" });
  await back.click();
  await expect(back).toBeHidden();
  await expect(picker).toBeFocused();
  await expect.poll(async () => (await metrics()).releases - before.releases).toBeGreaterThanOrEqual(50 * 3);
  expect(extraRequests).toEqual([]);
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
  await page.getByLabel("选择 GLB 文件").setInputFiles({
    name: "camera-preview.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(syntheticGlb()),
  });
  await expect(page.getByText(/camera-preview.glb：4 个三角面/)).toBeVisible();
  await expect.poll(async () => (await read()).modelViewMatrix[14]).not.toBeCloseTo(narrow.modelViewMatrix[14], 4);
  await page.getByRole("button", { name: "返回合成场景" }).click();
  await expect.poll(async () => (await read()).modelViewMatrix[14]).toBeCloseTo(narrow.modelViewMatrix[14], 4);
  await expect(press).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "恢复全景" }).click();
  await expect.poll(async () => (await read()).modelViewMatrix[14]).toBeCloseTo(overviewDepth, 6);
  await expect(press).toHaveAttribute("aria-pressed", "false");
});

test("a valid flat GLB with its front face away from the camera still writes pixels", async ({ page }) => {
  await page.addInitScript(() => {
    window.flatPixels = 0;
    const proto = WebGL2RenderingContext.prototype;
    const draw = proto.drawElements;
    proto.drawElements = function (...args) {
      if (args[0] !== this.TRIANGLES || args[1] !== 3) return draw.apply(this, args);
      const sample = new Uint8Array(64 * 64 * 4);
      const after = new Uint8Array(sample.length);
      const x = Math.floor(this.drawingBufferWidth / 2) - 32;
      const y = Math.floor(this.drawingBufferHeight / 2) - 32;
      this.readPixels(x, y, 64, 64, this.RGBA, this.UNSIGNED_BYTE, sample);
      const result = draw.apply(this, args);
      this.readPixels(x, y, 64, 64, this.RGBA, this.UNSIGNED_BYTE, after);
      let changed = 0;
      for (let i = 0; i < sample.length; i += 4) {
        if (sample[i] !== after[i] || sample[i + 1] !== after[i + 1] || sample[i + 2] !== after[i + 2]) changed++;
      }
      window.flatPixels = Math.max(window.flatPixels, changed);
      return result;
    };
  });
  await page.goto("/");
  const flatGlb = Buffer.from(syntheticGlb((json, bin) => {
    Object.assign(json.accessors[0], { count: 3, min: [-1, 0, -1], max: [1, 0, 1] });
    json.bufferViews[0].byteLength = 36;
    json.accessors[1].count = 3;
    json.bufferViews[1].byteLength = 6;
    [-1, 0, -1, 1, 0, -1, 0, 0, 1].forEach((value, index) => bin.setFloat32(index * 4, value, true));
    [0, 1, 2].forEach((value, index) => bin.setUint16(48 + index * 2, value, true));
  }));
  await page.getByLabel("选择 GLB 文件").setInputFiles({
    name: "reverse-facing-flat.glb", mimeType: "model/gltf-binary", buffer: flatGlb,
  });
  await expect(page.getByText(/reverse-facing-flat.glb：1 个三角面/)).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.flatPixels)).toBeGreaterThan(50);
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

test("only rectangular equipment exposes horizontal anchored resizing", async ({ page }) => {
  await page.goto("/");
  const tool = page.getByRole("group", { name: "保持对侧不动地调整尺寸" });
  const face = tool.getByLabel("设备局部面");
  const horizontal = ["+X", "-X", "+Z", "-Z"];
  for (const [rectangle, irregular] of [
    ["传送带 04 · 正常", "机械臂 06 · 预警"],
    ["廊道 05 · 正常", "螺旋传送机 07 · 正常"],
  ]) {
    await page.getByRole("button", { name: rectangle }).click();
    for (const value of horizontal) await expect(face.locator(`option[value="${value}"]`)).toBeEnabled();
    await face.selectOption("-Z");

    const device = page.getByRole("button", { name: irregular });
    await device.click();
    for (const value of horizontal) await expect(face.locator(`option[value="${value}"]`)).toBeDisabled();
    await expect(face.locator('option[value="+Y"]')).toBeEnabled();
    await expect(face).toHaveValue("+Y");
    const before = await tool.getByRole("status").textContent();
    // Even a programmatic stale selection must not alter the device.
    await face.evaluate((select) => { select.value = "+X"; });
    await tool.getByRole("button", { name: "伸出 0.5 米" }).click();
    await expect(tool.getByRole("status")).toHaveText(before);
    await expect(face).toHaveValue("+Y");
    await tool.getByRole("button", { name: "伸出 0.5 米" }).click();
    await expect(tool.getByRole("status")).not.toHaveText(before);
    await expect(device).toHaveAttribute("aria-pressed", "true");
  }
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

test("local exporter-shaped GLB honors parent transform without network or texture loads", async ({ page }) => {
  await page.addInitScript(() => {
    window.exporterDraws = 0;
    const draw = WebGL2RenderingContext.prototype.drawElements;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      if (args[0] === this.TRIANGLES && args[1] === 6 && args[2] === this.UNSIGNED_INT) window.exporterDraws++;
      return draw.apply(this, args);
    };
  });
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const network = [];
  const draws = () => page.evaluate(() => window.exporterDraws);
  const before = await draws();
  page.on("request", (request) => network.push(request.url()));
  await page.getByLabel("选择 GLB 文件").setInputFiles({
    name: "exporter.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(exporterGlb()),
  });
  await expect(page.getByText("exporter.glb：2 个三角面，4 个顶点，3 × 2 × 1 米。")).toBeVisible();
  await expect.poll(draws).toBeGreaterThan(before);
  expect(network).toEqual([]);
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

test("irregular equipment shows only the grounded height handle", async ({ page }) => {
  await page.goto("/");
  for (const name of ["机械臂 06 · 预警", "螺旋传送机 07 · 正常"]) {
    await page.getByRole("button", { name }).click();
    await expect(page.locator('.resize-handle[data-face="+Y"]')).toBeVisible();
    for (const face of ["+X", "-X", "+Z", "-Z"]) {
      await expect(page.locator(`.resize-handle[data-face="${face}"]`)).toBeHidden();
    }
  }
});

test("a viewport change or same-device rebuild cancels an active resize drag", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "传送带 04 · 正常" }).click();
  const handle = page.locator('.resize-handle[data-face="+X"]');
  const size = page.getByRole("group", { name: "保持对侧不动地调整尺寸" }).getByRole("status");
  for (const change of ["viewport", "model"]) {
    await expect(handle).toBeVisible();
    const axis = (await handle.getAttribute("data-axis")).split(",").map(Number);
    const box = await handle.boundingBox();
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + axis[0] * 0.5, y + axis[1] * 0.5);
    // Change the state and release in the same task, before frame/resize observers can fire.
    await handle.evaluate((node, change) => {
      if (change === "viewport") {
        const viewport = document.querySelector("#viewport");
        viewport.style.width = (viewport.clientWidth - 40) + "px";
      } else {
        const selector = document.querySelector("#conveyor-length");
        selector.value = "8";
        selector.dispatchEvent(new Event("change"));
      }
      node.dispatchEvent(new PointerEvent("pointerup", { pointerId: 1, clientX: 100, clientY: 100 }));
    }, change);
    await page.mouse.up();
    await expect(size).toHaveText(`长 ${change === "model" ? 8 : 4} · 宽 1 · 高 0.9 米。`);
  }
});
