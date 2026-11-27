import { expect, test } from "@playwright/test";

test("Studio exports a local playback file and Player opens it without editor code or network calls", async ({ page, context }) => {
  await page.goto("/");
  const downloading = page.waitForEvent("download");
  await page.getByRole("button", { name: "生成离线放映文件" }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toBe("might-player-v1.json");
  const bytes = [];
  for await (const value of await download.createReadStream()) bytes.push(value);
  const payload = Buffer.concat(bytes);
  const studioScript = await page.locator('script[type="module"][src]').getAttribute("src");

  const player = await context.newPage();
  const urls = [];
  player.on("request", (request) => urls.push(request.url()));
  await player.goto("/player.html");
  await expect(player.getByRole("heading", { name: "离线只读放映" })).toBeVisible();
  expect(urls.some((url) => url.endsWith(studioScript.replace(/^\./, "")))).toBe(false);
  expect(urls.every((url) => new URL(url).origin === new URL(player.url()).origin)).toBe(true);
  const count = urls.length;
  await player.getByLabel("载入放映文件").setInputFiles({ name: "played.json", mimeType: "application/json", buffer: payload });
  await expect(player.getByRole("status").filter({ hasText: "正在放映 sample-workshop：7 个设备" })).toBeVisible();
  await expect(player.getByRole("button", { name: "传送带 04 · 正常" })).toBeVisible();
  await player.getByRole("button", { name: "传送带 04 · 正常" }).focus();
  await player.keyboard.press("Enter");
  await expect(player.getByText("已选：传送带 04。")).toBeVisible();
  await expect(player.getByRole("button", { name: "导出场景草稿" })).toHaveCount(0);

  const tampered = JSON.parse(payload.toString("utf8"));
  tampered.draft = tampered.draft.replace("sample-workshop", "wrong-screen");
  await player.getByLabel("载入放映文件").setInputFiles({
    name: "tampered.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(tampered)),
  });
  await expect(player.getByRole("alert")).toContainText("摘要与内容不一致");
  await expect(player.getByRole("status").filter({ hasText: "正在放映 sample-workshop" })).toBeVisible();
  expect(urls).toHaveLength(count);
});

test("Player tour starts only on request, advances in order and manual selection interrupts", async ({ page, context }) => {
  await page.goto("/");
  const d = page.waitForEvent("download");
  await page.getByRole("button", { name: "生成离线放映文件" }).click();
  const payload = [];
  for await (const chunk of await (await d).createReadStream()) payload.push(chunk);
  const player = await context.newPage();
  await player.clock.install();
  await player.goto("/player.html");
  await player.getByLabel("载入放映文件").setInputFiles({
    name: "tour.json", mimeType: "application/json", buffer: Buffer.concat(payload),
  });
  const start = player.getByRole("button", { name: "开始设备巡检" });
  await expect(start).toBeEnabled();
  await expect(player.getByRole("button", { name: "循环泵 01 · 正常" })).toHaveAttribute("aria-pressed", "false");
  await start.click();
  await expect(player.getByText("已选：循环泵 01。")).toBeVisible();
  await player.clock.runFor(3100);
  await expect(player.getByText("已选：成型机 02。")).toBeVisible();
  await player.getByRole("button", { name: "排风装置 03 · 告警" }).click();
  await expect(player.getByText("设备巡检已因手动选择暂停。")).toBeVisible();
  await player.clock.runFor(3100);
  await expect(player.getByText("已选：排风装置 03。")).toBeVisible();
});

test("authored names and dwell times survive Studio export into Player", async ({ page, context }) => {
  await page.goto("/");
  const editor = page.getByRole("region", { name: "巡检机位编排" });
  async function add(id, title, seconds) {
    await editor.getByLabel("机位设备").selectOption(id);
    await editor.getByLabel("机位名称").fill(title);
    await editor.getByLabel("停留（秒）").fill(String(seconds));
    await editor.getByRole("button", { name: "添加机位" }).click();
  }
  await add("fan-03", "排风观察", 2);
  await add("pump-01", "循环机位", 5);
  await expect(editor.getByRole("listitem")).toHaveText([/排风观察 · 2 秒/, /循环机位 · 5 秒/]);
  const downloading = page.waitForEvent("download");
  await page.getByRole("button", { name: "生成离线放映文件" }).click();
  const chunks = [];
  for await (const chunk of await (await downloading).createReadStream()) chunks.push(chunk);
  const player = await context.newPage();
  await player.clock.install();
  await player.goto("/player.html");
  await player.getByLabel("载入放映文件").setInputFiles({
    name: "authored-tour.json", mimeType: "application/json", buffer: Buffer.concat(chunks),
  });
  await player.getByRole("button", { name: "开始设备巡检" }).click();
  await expect(player.getByText("正在放映机位：排风观察。")).toBeVisible();
  await player.clock.runFor(2100);
  await expect(player.getByText("正在放映机位：循环机位。")).toBeVisible();
  await player.clock.runFor(3100);
  await expect(player.getByText("正在放映机位：循环机位。")).toBeVisible();
  await player.clock.runFor(2100);
  await expect(player.getByText("设备巡检已完成。")).toBeVisible();
});

test("authored HUD selection works on an ultrawide Player and omits unchecked cards", async ({ page, context }) => {
  await page.goto("/");
  const hud = page.getByRole("group", { name: "放映指标卡" });
  await hud.getByLabel("墙体").uncheck();
  await hud.getByLabel("区域").uncheck();
  const downloading = page.waitForEvent("download");
  await page.getByRole("button", { name: "生成离线放映文件" }).click();
  const chunks = [];
  for await (const chunk of await (await downloading).createReadStream()) chunks.push(chunk);
  const player = await context.newPage();
  await player.setViewportSize({ width: 2560, height: 720 });
  await player.goto("/player.html");
  await player.getByLabel("载入放映文件").setInputFiles({
    name: "hud.json", mimeType: "application/json", buffer: Buffer.concat(chunks),
  });
  const metrics = player.getByRole("region", { name: "场景指标" });
  await expect(metrics.locator("dt")).toHaveText(["设备", "告警"]);
  await expect(metrics.locator("dd")).toHaveText(["7", "1"]);
  expect(await player.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(2560);
});

test("edited Studio draft survives a fresh reopen and plays offline with the same tour and plan", async ({ page, context }) => {
  async function download(from, button, filename) {
    const pending = from.waitForEvent("download");
    await from.getByRole("button", { name: button }).click();
    const result = await pending;
    expect(result.suggestedFilename()).toBe(filename);
    const chunks = [];
    for await (const value of await result.createReadStream()) chunks.push(value);
    return { name: filename, mimeType: "application/json", buffer: Buffer.concat(chunks) };
  }
  const network = [];
  for (const event of ["request", "websocket"]) page.on(event, (record) => network.push(record.url()));
  await page.goto("/");
  const editor = (view) => view.getByRole("region", { name: "顶视平面绘图" });
  const device = (view) => view.getByRole("button", { name: "机械臂 06 · 预警", exact: true });
  const resize = (view) => view.getByRole("group", { name: "保持对侧不动地调整尺寸" });
  await device(page).click();
  await resize(page).getByLabel("设备局部面").selectOption("+Y");
  await resize(page).getByRole("button", { name: "伸出 0.5 米" }).click();

  const plan = editor(page);
  async function add(x, z) {
    await plan.getByLabel("X（米）").fill(String(x));
    const zInput = plan.getByLabel("Z（米）");
    await zInput.fill(String(z));
    await zInput.press("Enter");
  }
  await add(0, 0);
  await add(4, 0);
  await plan.getByLabel("绘制模式").selectOption("zone");
  for (const [x, z] of [[0, 0], [4, 0], [4, 3], [0, 3]]) await add(x, z);
  await plan.getByRole("button", { name: "闭合区域" }).click();
  await page.getByLabel("机位设备").selectOption("robot-06");
  await page.getByLabel("机位名称").fill("机械臂围栏巡检");
  await page.getByRole("button", { name: "添加机位" }).click();
  const hud = (view) => view.getByRole("group", { name: "放映指标卡" });
  await hud(page).getByLabel("设备").uncheck();
  await hud(page).getByLabel("告警").uncheck();
  const draft = await download(page, "导出场景草稿", "might-scene-draft.json");

  const reopened = await context.newPage();
  for (const event of ["request", "websocket"]) reopened.on(event, (record) => network.push(record.url()));
  await reopened.goto("/");
  await expect(editor(reopened).locator(".plan-wall")).toHaveCount(0);
  await reopened.getByLabel("读取场景草稿").setInputFiles(draft);
  await expect(reopened.locator("#snapshot-status")).toContainText("已读取");
  await expect(editor(reopened).locator(".plan-wall")).toHaveAttribute("points", "0,0 40,0");
  await expect(editor(reopened).getByRole("listitem")).toContainText("12 平方米");
  await expect(reopened.locator("#tour-edit-list")).toContainText("机械臂围栏巡检");
  await expect(hud(reopened).getByLabel("设备")).not.toBeChecked();
  await device(reopened).click();
  await expect(resize(reopened).getByRole("status")).toHaveText("长 2 · 宽 1.8 · 高 2.7 米。");
  const playback = await download(reopened, "生成离线放映文件", "might-player-v1.json");
  const restored = JSON.parse(JSON.parse(playback.buffer.toString()).draft);
  expect(restored).toEqual(JSON.parse(draft.buffer.toString()));

  const player = await context.newPage();
  await player.clock.install();
  const playerNetwork = [];
  for (const event of ["request", "websocket"]) player.on(event, (record) => playerNetwork.push(record.url()));
  await player.goto("/player.html");
  const count = playerNetwork.length;
  await player.getByLabel("载入放映文件").setInputFiles(playback);
  await expect(player.getByText("三维放映已就绪。", { exact: true })).toBeVisible();
  const metrics = player.getByRole("region", { name: "场景指标" });
  await expect(metrics.locator("dt")).toHaveText(["墙体", "区域"]);
  await expect(metrics.locator("dd")).toHaveText(["1", "1"]);
  await player.getByRole("button", { name: "开始设备巡检" }).click();
  await expect(player.getByText("正在放映机位：机械臂围栏巡检。")).toBeVisible();
  await expect(device(player)).toHaveAttribute("aria-pressed", "true");
  expect(playerNetwork).toHaveLength(count);
  expect([...network, ...playerNetwork].filter((url) => new URL(url).origin !== new URL(page.url()).origin)).toEqual([]);
});
