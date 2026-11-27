import { expect, test } from "@playwright/test";
import { exporterGlb, multiMeshGlb, syntheticGlb } from "../core/glbFixture.ts";

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
  await page.getByLabel("长度（米）", { exact: true }).selectOption("8");
  await expect(page.getByRole("status").filter({ hasText: "当前长度：8 米" })).toBeVisible();
  await expect(conveyor).toHaveAttribute("aria-pressed", "true");
  await expect.poll(getDraws).toBeGreaterThan(beforeRebuild);

  await page.evaluate(() => {
    const gl = document.querySelector("#viewport").getContext("webgl2");
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
  await expect(page.locator("#viewport")).toBeHidden();
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
  await page.addInitScript(() => {
    window.lastFrameDraws = 0;
    window.planBufferReleases = 0;
    const gl = WebGL2RenderingContext.prototype;
    const clear = gl.clear;
    const draw = gl.drawElements, remove = gl.deleteBuffer;
    gl.clear = function (...args) { window.lastFrameDraws = 0; return clear.apply(this, args); };
    gl.drawElements = function (...args) { window.lastFrameDraws++; return draw.apply(this, args); };
    gl.deleteBuffer = function (...args) { window.planBufferReleases++; return remove.apply(this, args); };
  });
  await page.goto("/");
  const frameDraws = () => page.evaluate(() => window.lastFrameDraws);
  await expect.poll(frameDraws).toBeGreaterThan(0);
  const baselineDraws = await frameDraws();
  const editor = page.getByRole("region", { name: "顶视平面绘图" });
  const map = editor.getByRole("img", { name: "顶视图，横轴 X，纵轴 Z" });
  await expect(map).toBeVisible();
  const box = await map.boundingBox();
  await map.click({ position: { x: box.width * 0.5, y: box.height * 0.5 } });
  await map.click({ position: { x: box.width * 0.7, y: box.height * 0.55 }, modifiers: ["Shift"] });
  await expect(editor.locator(".plan-wall")).toHaveAttribute("points", "0,0 40,0");
  await expect(page.getByRole("status").filter({ hasText: "三维墙体 1 条，高 3 米，厚 0.2 米。" })).toBeVisible();
  await expect.poll(frameDraws).toBeGreaterThan(baselineDraws);
  await expect(editor.getByRole("status")).toContainText("墙段 1 条");
  await editor.getByLabel("绘制模式").selectOption("zone");
  await editor.getByLabel("吸附网格").selectOption("0.1");
  const undo = editor.getByRole("button", { name: "撤销墙段或区域" });
  const redo = editor.getByRole("button", { name: "重做墙段或区域" });
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
  await expect(undo).toBeDisabled();
  await editor.getByRole("button", { name: "闭合区域" }).click();
  await expect(editor.getByRole("listitem")).toContainText("12 平方米");
  await expect(editor.locator(".plan-zone")).toHaveAttribute("points", "0,0 40,0 40,30 0,30");
  await expect(editor.locator(".plan-wall")).toHaveCount(1);
  await undo.click();
  await expect(editor.locator(".plan-zone")).toHaveCount(0);
  await expect(editor.locator(".plan-wall")).toHaveCount(1);
  const released = () => page.evaluate(() => window.planBufferReleases);
  const before = await released();
  await undo.click();
  await expect(editor.locator(".plan-wall")).toHaveCount(0);
  await expect.poll(released).toBeGreaterThan(before);
  await expect.poll(frameDraws).toBe(baselineDraws);
  await redo.click();
  await expect(editor.locator(".plan-wall")).toHaveAttribute("points", "0,0 40,0");
  await expect.poll(frameDraws).toBeGreaterThan(baselineDraws);
  await redo.click();
  await expect(editor.locator(".plan-zone")).toHaveAttribute("points", "0,0 40,0 40,30 0,30");
  await expect(editor.getByRole("listitem")).toHaveText("区域 2：12 平方米");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test("viewport resize handle commits on release and Escape cancels without resizing", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "传送带 04 · 正常" }).click();
  const size = page.getByRole("group", { name: "保持对侧不动地调整尺寸" }).getByRole("status");
  await expect(size).toHaveText("长 4 · 宽 1 · 高 0.9 米。");
  const undo = page.getByRole("button", { name: "撤销上次尺寸调整" });
  const redo = page.getByRole("button", { name: "重做尺寸调整" });
  const conveyor = async () => {
    const pending = page.waitForEvent("download");
    await page.getByRole("button", { name: "导出场景草稿" }).click();
    const chunks = [];
    for await (const value of await (await pending).createReadStream()) chunks.push(value);
    return JSON.parse(Buffer.concat(chunks).toString()).scene.devices.find(({ id }) => id === "conveyor-04");
  };
  const original = await conveyor();
  const handle = page.locator('#resize-gizmo [data-face="+X"]');
  const drag = async (cancel) => {
    await expect(handle).toBeVisible();
    await handle.scrollIntoViewIfNeeded();
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
  await expect(undo).toBeDisabled();
  await drag(false);
  await expect.poll(async () => Number((await size.textContent()).match(/长 ([\d.]+)/)?.[1])).toBeGreaterThan(4.3);
  await expect(size).toContainText("宽 1 · 高 0.9 米");
  const moved = await conveyor();
  expect(moved.position[0]).toBeGreaterThan(original.position[0]);
  await undo.click();
  await expect(size).toHaveText("长 4 · 宽 1 · 高 0.9 米。");
  expect(await conveyor()).toEqual(original);
  await redo.click();
  expect(await conveyor()).toEqual(moved);
  await undo.click();
  await page.getByRole("button", { name: "伸出 0.5 米" }).click();
  await expect(size).toHaveText("长 4.5 · 宽 1 · 高 0.9 米。");
  await expect(redo).toBeDisabled();
  await expect(page.getByRole("button", { name: "传送带 04 · 正常" })).toHaveAttribute("aria-pressed", "true");
});

test("session asset bank registers a valid GLB without upload and deduplicates content", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const network = [];
  page.on("request", (request) => network.push(request.url()));
  const picker = page.getByLabel("选择 GLB 文件");
  const selectFile = (name) => picker.setInputFiles({ name, mimeType: "model/gltf-binary", buffer: Buffer.from(syntheticGlb()) });
  const bank = page.getByRole("region", { name: "会话模型库" });
  const register = page.getByRole("button", { name: "加入会话模型库" });
  await selectFile("asset-a.glb");
  const modelUnit = page.getByLabel("模型源单位");
  await modelUnit.selectOption("mm");
  await register.click();
  await expect(bank.getByRole("status")).toContainText("模型库共 1 个");
  await expect(bank.getByText(/源单位：毫米；0.002 × 0.002 × 0.002 米/)).toBeVisible();
  const downloading = page.waitForEvent("download");
  await bank.getByRole("button", { name: "导出本地模型清单" }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe("might-local-asset-metadata-v1.json");
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  const manifest = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  expect(manifest.format).toBe("might-local-asset-metadata-v1");
  expect(manifest.assets).toHaveLength(1);
  expect(manifest.assets[0]).toMatchObject({ sourceUnit: "mm", dimensionsMetres: [0.002, 0.002, 0.002],
    metrics: { vertices: 4, triangles: 4 }, id: expect.stringMatching(/^[0-9a-f]{64}$/) });
  expect(manifest.assets[0]).not.toHaveProperty("bytes");
  expect(manifest.assets[0]).not.toHaveProperty("url");
  await selectFile("renamed.glb");
  await modelUnit.selectOption("m");
  await register.click();
  await expect(bank.getByRole("status")).toContainText("文件内容相同，沿用 asset-a.glb");
  await expect(bank.getByRole("button", { name: "预览 renamed.glb" })).toHaveCount(0);
  await page.getByRole("button", { name: "返回合成场景" }).click();
  await bank.getByRole("button", { name: "预览 asset-a.glb" }).click();
  await expect(modelUnit).toHaveValue("mm");
  await expect(page.getByText("asset-a.glb：4 个三角面，4 个顶点，0.002 × 0.002 × 0.002 米。")).toBeVisible();
  await bank.getByRole("button", { name: "移除 asset-a.glb" }).click();
  await expect(bank.getByRole("status")).toContainText("已从会话模型库移除");
  await expect(bank.getByRole("button")).toHaveCount(0);
  expect(network).toEqual([]);
});

test("Studio locally imports and exports a versioned draft and rejects invalid geometry atomically", async ({ page }) => {
  await page.goto("/");
  const panel = page.getByRole("region", { name: "场景草稿文件" });
  const file = panel.getByLabel("读取场景草稿");
  const draft = {
    snapshotVersion: 1,
    scene: { schemaVersion: 1, screenId: "round-trip", devices: [
      { id: "synthetic-well", label: "合成设备", primitive: "box", position: [0, 0, 0], size: [1, 2, 3], state: "normal" },
    ] },
    floorplan: {
      walls: [{ id: 1, start: [0, 0], end: [20, 0] }],
      zones: [{ id: 2, points: [[0, 0], [20, 0], [20, 30], [0, 30]] }],
    },
  };
  async function load(value) {
    await file.setInputFiles({ name: "draft.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(value)) });
  }
  await load(draft);
  await expect(panel.getByRole("status")).toContainText("已读取 round-trip，共 1 个设备");
  await expect(page.getByRole("button", { name: "合成设备 · 正常" })).toBeVisible();
  await expect(page.getByRole("list", { name: "已闭合区域" })).toContainText("区域 2：6 平方米");
  await load({ ...draft, scene: { ...draft.scene, screenId: "invalid" }, floorplan: { ...draft.floorplan,
    zones: [{ id: 2, points: [[0, 0], [20, 30], [0, 30], [30, 0]] }] } });
  await expect(panel.getByRole("alert")).toBeVisible();
  await expect(panel.getByRole("status")).toContainText("round-trip");
  await expect(page.getByRole("list", { name: "已闭合区域" })).toContainText("区域 2：6 平方米");

  const downloading = page.waitForEvent("download");
  await panel.getByRole("button", { name: "导出场景草稿" }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe("might-scene-draft.json");
  const stream = await download.createReadStream();
  const bytes = [];
  for await (const value of stream) bytes.push(value);
  expect(JSON.parse(Buffer.concat(bytes).toString("utf8"))).toEqual(draft);
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

test("local multi-mesh preview computes combined dimensions and preserves previous selection on invalid graph", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: "三维视口已就绪" })).toBeVisible();
  const network = [];
  page.on("request", (request) => network.push(request.url()));
  const input = page.getByLabel("选择 GLB 文件");
  await input.setInputFiles({ name: "two-meshes.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(multiMeshGlb((doc) => {
    doc.nodes[0].name = "<img>";
    doc.meshes[1].name = "设备部件";
  })) });
  const valid = page.getByText("two-meshes.glb：2 个三角面，6 个顶点，5 × 2 × 3 米。");
  await expect(valid).toBeVisible();
  const details = page.locator("#model-hierarchy");
  await details.locator("summary").click();
  const root = details.locator("#model-nodes > li");
  await expect(root).toContainText("<img>");
  await expect(root.locator("img")).toHaveCount(0);
  await expect(root.locator("li")).toHaveText([
    "节点 #1 / 网格 #0（3 顶点，1 面）", "节点 #2 / 网格 #1 · 设备部件（3 顶点，1 面）",
  ]);
  const bad = multiMeshGlb((doc) => { doc.nodes[2].children = [0]; });
  await input.setInputFiles({ name: "cycle.glb", mimeType: "model/gltf-binary", buffer: Buffer.from(bad) });
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(valid).toBeVisible();
  await expect(root.locator("li")).toHaveCount(2);
  await page.getByRole("button", { name: "加入会话模型库" }).click();
  await expect(page.locator("#model-bank-info")).toContainText("模型库共 1 个。");
  await page.getByRole("button", { name: "返回合成场景" }).click();
  await expect(details).toBeHidden();
  await expect(details.locator("li")).toHaveCount(0);
  await page.getByRole("button", { name: "预览 two-meshes.glb", exact: true }).click();
  await details.locator("summary").click();
  await expect(root.locator("li")).toHaveCount(2);
  expect(network).toEqual([]);
});

test("captures the validated preview as one bounded local thumbnail and clears it on replacement", async ({ page }) => {
  await page.goto("/");
  const network = [];
  page.on("request", (request) => network.push(request.url()));
  const generate = page.getByRole("button", { name: "生成本地缩略图" });
  const thumb = page.locator("#model-thumbnail");
  await expect(generate).toBeHidden();
  const input = page.getByLabel("选择 GLB 文件");
  const choose = (name, bytes) => input.setInputFiles({ name, mimeType: "model/gltf-binary", buffer: Buffer.from(bytes) });
  await choose("thumbnail-first.glb", multiMeshGlb());
  await generate.click();
  await expect(thumb).toBeVisible();
  await expect(thumb).toHaveAccessibleName("当前模型的本地缩略图");
  await expect(thumb).toHaveAttribute("width", "128");
  await expect(thumb).toHaveAttribute("height", "96");
  const colors = await thumb.evaluate((image) => {
    const { data } = image.getContext("2d").getImageData(0, 0, image.width, image.height);
    const unique = new Set();
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3]) unique.add(data[i] * 65536 + data[i + 1] * 256 + data[i + 2]);
    }
    return unique.size;
  });
  expect(colors).toBeGreaterThan(4);
  await choose("thumbnail-second.glb", syntheticGlb());
  await expect(page.locator("#model-result")).toContainText("thumbnail-second.glb");
  await expect(thumb).toBeHidden();
  const cleared = await thumb.evaluate((image) =>
    image.getContext("2d").getImageData(0, 0, image.width, image.height).data.every((value) => value === 0));
  expect(cleared).toBe(true);
  expect(network).toEqual([]);
});
