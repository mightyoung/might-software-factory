import { expect, test } from "@playwright/test";
import { syntheticGlb } from "../core/glbFixture.ts";

test("offline verifier checks downloaded manifest, rejects replaced model and leaves bank unchanged", async ({ page }) => {
  await page.goto("/");
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
  await model.setInputFiles({ name: "renamed.glb", mimeType: "model/gltf-binary", buffer: bytes });
  await verify.getByRole("button", { name: "核验本地文件" }).click();
  await expect(verify.getByRole("status")).toContainText("核验通过：source.glb，源单位 毫米");
  await model.setInputFiles({ name: "source.glb", mimeType: "model/gltf-binary",
    buffer: Buffer.from(syntheticGlb((doc) => { doc.asset.generator = "changed"; })) });
  await expect(verify.getByRole("status")).not.toContainText("核验通过");
  await verify.getByRole("button", { name: "核验本地文件" }).click();
  await expect(verify.getByRole("status")).toContainText("SHA-256 与清单中的任何条目都不匹配");
  await expect(bank.getByRole("status")).toContainText("模型库共 1 个");
  expect(urls).toEqual([]);
});
