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
