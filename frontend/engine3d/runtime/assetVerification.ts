import { MAX_MANIFEST_BYTES, verifyLocalAssetReference } from "../core/localAssetVerification.ts";
import { MAX_GLB_BYTES } from "../core/glbIntake.ts";

const form = document.querySelector<HTMLFormElement>("#asset-verification-form")!;
const manifest = document.querySelector<HTMLInputElement>("#asset-verification-list")!;
const model = document.querySelector<HTMLInputElement>("#asset-verification-model")!;
const result = document.querySelector("#asset-verification-result")!;
let revision = 0;

function reset() {
  revision++;
  result.textContent = "等待核验所选文件。";
}
manifest.addEventListener("change", reset);
model.addEventListener("change", reset);
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const mine = ++revision;
  try {
    const listFile = manifest.files?.[0];
    const modelFile = model.files?.[0];
    if (!listFile || !listFile.name.toLowerCase().endsWith(".json") || listFile.size > MAX_MANIFEST_BYTES)
      throw new Error("请选择不超过 16 KiB 的 JSON 清单。");
    if (!modelFile || !modelFile.name.toLowerCase().endsWith(".glb") || modelFile.size > MAX_GLB_BYTES)
      throw new Error("请选择不超过 8 MiB 的 GLB 文件。");
    result.textContent = "正在核验本地文件…";
    const [raw, bytes] = await Promise.all([listFile.text(), modelFile.arrayBuffer()]);
    if (mine !== revision) return;
    const found = await verifyLocalAssetReference(raw, bytes);
    if (mine !== revision) return;
    const unit = found.sourceUnit === "mm" ? "毫米" : "米";
    result.textContent = `核验通过：${found.name}，源单位 ${unit}，${found.metrics.triangles} 个三角面；SHA-256 与清单一致。`;
  } catch (failure) {
    if (mine === revision) result.textContent = "核验未通过：" + (failure instanceof Error ? failure.message : "无法读取本地文件。");
  }
});
window.addEventListener("pagehide", () => { revision++; });
