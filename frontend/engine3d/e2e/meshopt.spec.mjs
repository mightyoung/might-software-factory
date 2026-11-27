import { expect, test } from "@playwright/test";
import { meshoptFixture } from "../core/meshoptFixture.ts";

test("experimental Meshopt worker displays real decoded geometry and releases it; ordinary import still rejects", async ({ page }) => {
  await page.addInitScript(() => {
    window.meshoptWorkers = { started: 0, terminated: 0, hold: false, posted: 0 };
    const Native = window.Worker;
    window.Worker = class extends Native {
      constructor(url, options) {
        super(url, options);
        this.meshopt = String(url).includes("meshopt.worker");
        if (this.meshopt) window.meshoptWorkers.started++;
      }
      postMessage(...args) {
        if (this.meshopt) window.meshoptWorkers.posted++;
        if (!(this.meshopt && window.meshoptWorkers.hold)) super.postMessage(...args);
      }
      terminate() {
        if (this.meshopt) window.meshoptWorkers.terminated++;
        super.terminate();
      }
    };
    const proto = WebGL2RenderingContext.prototype;
    const draw = proto.drawArrays, remove = proto.deleteBuffer;
    window.meshoptGL = { draws: 0, releases: 0 };
    proto.drawArrays = function (...args) {
      if (args[0] === this.TRIANGLES && args[2] === 6) window.meshoptGL.draws++;
      return draw.apply(this, args);
    };
    proto.deleteBuffer = function (...args) {
      window.meshoptGL.releases++;
      return remove.apply(this, args);
    };
  });
  const requests = [], workers = [];
  page.on("worker", (worker) => workers.push(worker.url()));
  page.on("request", (request) => requests.push(request.url()));
  await page.goto("/");
  await expect(page.locator("#status")).toContainText("三维视口已就绪");
  const bytes = Buffer.from(await meshoptFixture());
  const model = (name, buffer = bytes) => ({ name, mimeType: "model/gltf-binary", buffer });
  await page.getByLabel("选择 GLB 文件", { exact: true }).setInputFiles(model("default-denied.glb"));
  await expect(page.locator("#model-error")).toContainText("不支持");
  expect(await page.evaluate(() => window.meshoptWorkers.started)).toEqual(0);

  await page.getByText("实验：本地 Meshopt 预览", { exact: true }).click();
  const input = page.getByLabel("选择实验 Meshopt GLB");
  const close = page.getByRole("button", { name: "关闭实验预览" });
  const outcome = page.locator("#meshopt-status");
  const gl = () => page.evaluate(() => ({ ...window.meshoptGL }));
  const stats = () => page.evaluate(() => ({ ...window.meshoptWorkers }));
  const initial = await gl();
  await input.setInputFiles(model("worker-good.glb"));
  await expect(outcome).toContainText("实验模型已就绪");
  await expect(page.locator("#model-result")).toContainText("worker-good.glb：2 个三角面，6 个顶点，1 × 2 × 3 米");
  await expect.poll(async () => (await gl()).draws).toBeGreaterThan(initial.draws);
  await expect(page.getByRole("button", { name: "加入会话模型库" })).toBeHidden();
  await expect.poll(async () => (await stats()).terminated).toBe(1);

  const malformed = await meshoptFixture((_doc, packed) => { packed[0] = 0; });
  await input.setInputFiles(model("bad.glb", Buffer.from(malformed)));
  await expect(outcome).toContainText("无法解码");
  await expect(page.locator("#model-result")).toContainText("worker-good.glb");

  await page.evaluate(() => { window.meshoptWorkers.hold = true; });
  await input.setInputFiles(model("held.glb"));
  await expect.poll(async () => (await stats()).posted).toBe(3);
  await page.evaluate(() => { window.meshoptWorkers.hold = false; });
  await input.setInputFiles(model("replacement.glb"));
  await expect(page.locator("#model-result")).toContainText("replacement.glb");
  await expect.poll(async () => (await stats()).terminated).toBe(4);
  const beforeClose = await gl();
  await close.click();
  await expect(page.locator("#model-result")).toHaveText("");
  await expect.poll(async () => (await gl()).releases).toBeGreaterThan(beforeClose.releases);
  expect(workers.some((url) => url.includes("meshopt.worker-"))).toBe(true);
  expect([...requests, ...workers].every((url) => new URL(url).origin === new URL(page.url()).origin)).toBe(true);
});
