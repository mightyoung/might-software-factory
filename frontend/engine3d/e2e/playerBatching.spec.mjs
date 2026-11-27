import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";

const file = (count, far = false) => {
  const devices = Array.from({ length: count }, (_, i) => ({
    id: "probe-" + i, label: "合成方块 " + i, primitive: "box", state: "normal",
    size: [1, 1, 1], position: [(i % 5) - 2, 0, Math.floor(i / 5) - 1],
  }));
  if (far) devices.push(...[500, 501].map((x, i) => ({
    id: "far-" + i, label: "远端方块 " + i, primitive: "box", state: "normal", size: [1, 1, 1], position: [x, 0, 0],
  })));
  const draft = JSON.stringify({
    snapshotVersion: 1, scene: { schemaVersion: 1, screenId: "batch-test", devices }, floorplan: { walls: [], zones: [] },
  });
  return {
    name: "synthetic-batch.json", mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify({ format: "might-player-v1", draft,
      digest: createHash("sha256").update(draft).digest("hex") })),
  };
};

test("Player batches identical immutable devices, still focuses, and frees the batch on replacement", async ({ page }) => {
  await page.addInitScript(() => {
    window.instanceCounts = [];
    window.deletedBuffers = 0;
    const proto = WebGL2RenderingContext.prototype;
    const draw = proto.drawElementsInstanced;
    proto.drawElementsInstanced = function (...args) {
      window.instanceCounts.push(args[4]);
      return draw.apply(this, args);
    };
    const remove = proto.deleteBuffer;
    proto.deleteBuffer = function (...args) {
      window.deletedBuffers++;
      return remove.apply(this, args);
    };
  });
  await page.goto("/player.html");
  const chooser = page.getByLabel("载入放映文件");
  await chooser.setInputFiles(file(20, true));
  await expect(page.getByRole("status").filter({ hasText: "22 个设备" })).toBeVisible();
  const calls = () => page.evaluate(() => window.instanceCounts);
  await expect.poll(calls).toContain(20);
  expect(await calls()).not.toContain(2); // the distant cluster remains frustum-culled
  const before = (await calls()).length;
  const last = page.getByRole("button", { name: "合成方块 19 · 正常" });
  await last.focus();
  await page.keyboard.press("Enter");
  await expect(last).toHaveAttribute("aria-pressed", "true");
  await expect.poll(async () => (await calls()).length).toBeGreaterThan(before);
  await page.getByRole("button", { name: "远端方块 1 · 正常" }).click();
  await expect.poll(calls).toContain(2);
  const removedBefore = await page.evaluate(() => window.deletedBuffers);
  await chooser.setInputFiles(file(1));
  await expect(page.getByRole("status").filter({ hasText: "1 个设备" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.deletedBuffers)).toBeGreaterThan(removedBefore);
  await expect(last).toHaveCount(0);
});
