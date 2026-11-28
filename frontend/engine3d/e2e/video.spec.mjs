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

test("synthetic RAF stops on hide or scroll-away before releasing media", async ({ page }) => {
  await page.addInitScript(() => {
    window.syntheticDraws = 0;
    const fill = CanvasRenderingContext2D.prototype.fillText;
    CanvasRenderingContext2D.prototype.fillText = function (text, ...rest) {
      if (text === "SYNTHETIC A") window.syntheticDraws++;
      return fill.call(this, text, ...rest);
    };
    Object.defineProperty(document, "hidden", { configurable: true, get: () => window.testHidden === true });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "打开信号 A" }).click();
  const card = page.locator('[data-slot="A"]');
  const draws = () => page.evaluate(() => window.syntheticDraws);
  const settle = () => page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 300)));
  await card.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await expect.poll(draws).toBeGreaterThan(1);
  const hidden = await page.evaluate(() => {
    window.testHidden = true;
    document.dispatchEvent(new Event("visibilitychange"));
    return window.syntheticDraws;
  });
  await settle();
  expect(await draws()).toBe(hidden);
  await page.evaluate(() => { window.testHidden = false; document.dispatchEvent(new Event("visibilitychange")); });
  await expect.poll(draws).toBeGreaterThan(hidden);
  const offscreen = await page.evaluate(() => { window.scrollTo(0, 0); return window.syntheticDraws; });
  await settle();
  expect(await draws()).toBe(offscreen);
  expect(await card.locator("video").evaluate((node) => node.srcObject.getVideoTracks()[0].readyState)).toBe("live");
  await card.evaluate((node) => node.scrollIntoView({ block: "center" }));
  await expect.poll(draws).toBeGreaterThan(offscreen);
});

test("synthetic video handles unavailable Canvas 2D without attaching media", async ({ page }) => {
  await page.addInitScript(() => {
    const get = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (kind, ...rest) {
      return kind === "2d" ? null : get.call(this, kind, ...rest);
    };
  });
  await page.goto("/");
  const region = page.getByRole("region", { name: "合成视频窗口" });
  await region.getByRole("button", { name: "打开信号 A" }).click();
  await expect(region.getByRole("status")).toHaveText("当前浏览器无法启动合成视频。");
  await expect(region.locator("video")).toHaveCount(0);
});
