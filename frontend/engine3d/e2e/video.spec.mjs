import { expect, test } from "@playwright/test";

test("synthetic video hard-caps four channels and truly stops evicted tracks", async ({ page }) => {
  await page.goto("/");
  const video = page.getByRole("region", { name: "合成视频窗口" });
  await expect(video.getByRole("status")).toHaveText("当前 0/4 路合成视频。");
  const requests = [];
  page.on("request", (request) => requests.push(request.url()));
  for (const id of ["A", "B", "C", "D"]) await video.getByRole("button", { name: `打开信号 ${id}` }).click();
  await expect(video.getByRole("status")).toHaveText("当前 4/4 路合成视频。");
  await page.evaluate(() => {
    window.aTrack = document.querySelector('[data-slot="A"] video').srcObject.getVideoTracks()[0];
    window.bTrack = document.querySelector('[data-slot="B"] video').srcObject.getVideoTracks()[0];
  });
  await video.getByRole("button", { name: "打开信号 A" }).click();
  await video.getByRole("button", { name: "打开信号 E" }).click();
  await expect(video.getByRole("heading", { name: "合成信号 B" })).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.bTrack.readyState)).toBe("ended");
  expect(await page.evaluate(() => window.aTrack.readyState)).toBe("live");
  await expect(video.locator("video")).toHaveCount(4);
  await video.getByRole("button", { name: "停止 A" }).click();
  await expect(video.getByRole("button", { name: "打开信号 A" })).toBeFocused();
  await expect.poll(() => page.evaluate(() => window.aTrack.readyState)).toBe("ended");
  await expect(video.getByRole("status")).toHaveText("当前 3/4 路合成视频。");
  expect(requests).toEqual([]);
});
