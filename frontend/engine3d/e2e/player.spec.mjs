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
