import { expect, test } from "@playwright/test";
import { syntheticGlb } from "../core/glbFixture.ts";

test("offline verifier checks downloaded manifest, rejects replaced model and leaves bank unchanged", async ({ page }) => {
  await page.addInitScript(() => {
    window.referenceDraws = 0;
    const original = WebGL2RenderingContext.prototype.drawElements;
    WebGL2RenderingContext.prototype.drawElements = function (...args) {
      if (args[0] === this.LINES) window.referenceDraws++;
      return original.apply(this, args);
    };
  });
  await page.goto("/");
  await expect(page.locator("#status")).toContainText("已就绪");
  const urls = [];
  page.on("request", ({ url }) => urls.push(url()));
  const bytes = Buffer.from(syntheticGlb());
  await page.getByLabel("选择 GLB 文件").setInputFiles({ name: "source.glb", mimeType: "model/gltf-binary", buffer: bytes });
  await page.getByLabel("模型源单位").selectOption("mm");
  await page.getByRole("button", { name: "加入会话模型库" }).click();
  const bank = page.getByRole("region", { name: "会话模型库" });
  await expect(bank.getByRole("status")).toContainText("模型库共 1 个");
  const pending = page.waitForEvent("download");
  await bank.getByRole("button", { name: "导出本地模型清单" }).click();
  const chunks = [];
  for await (const chunk of await (await pending).createReadStream()) chunks.push(chunk);

  const verify = page.getByRole("region", { name: "本地模型清单核验" });
  await verify.getByLabel("核验清单").setInputFiles({ name: "manifest.json", mimeType: "application/json", buffer: Buffer.concat(chunks) });
  const model = verify.getByLabel("核验模型");
  const place = verify.getByRole("button", { name: "放置临时参考" });
  await expect(place).toBeDisabled();
  await model.setInputFiles({ name: "renamed.glb", mimeType: "model/gltf-binary", buffer: bytes });
  await verify.getByRole("button", { name: "核验本地文件" }).click();
  await expect(verify.locator("#asset-verification-result")).toContainText("核验通过：source.glb，源单位 毫米");
  const before = await page.evaluate(() => window.referenceDraws);
  await place.click();
  await expect(verify.locator("#reference-placement-status")).toContainText("已放置临时参考：source.glb");
  await expect.poll(() => page.evaluate(() => window.referenceDraws)).toBeGreaterThan(before);
  const exportDraft = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出场景草稿" }).click();
  const exported = [];
  for await (const chunk of await (await exportDraft).createReadStream()) exported.push(chunk);
  const draft = Buffer.concat(exported).toString("utf8");
  expect(JSON.parse(draft).scene.devices).toHaveLength(7);
  expect(draft).not.toContain("source.glb");
  await model.setInputFiles({ name: "source.glb", mimeType: "model/gltf-binary",
    buffer: Buffer.from(syntheticGlb((doc) => { doc.asset.generator = "changed"; })) });
  await expect(verify.locator("#asset-verification-result")).not.toContainText("核验通过");
  await expect(place).toBeDisabled();
  await verify.getByRole("button", { name: "核验本地文件" }).click();
  await expect(verify.locator("#asset-verification-result")).toContainText("SHA-256 与清单中的任何条目都不匹配");
  await expect(bank.getByRole("status")).toContainText("模型库共 1 个");
  await verify.getByRole("button", { name: "移除临时参考" }).click();
  await expect(verify.locator("#reference-placement-status")).toContainText("当前场景没有临时参考");
  expect(urls).toEqual([]);
});
