import { MAX_MANIFEST_BYTES, verifyLocalAssetReference } from "../core/localAssetVerification.ts";
import { MAX_GLB_BYTES } from "../core/glbIntake.ts";

import type { LocalAssetMetadata } from "../core/LocalAssetBank.ts";
import type { ReferencePoint } from "../core/visualReference.ts";

export function mountAssetVerification(
  place: (asset: LocalAssetMetadata, bytes: ArrayBuffer, point: ReferencePoint) => void,
  clearReference: () => void,
) {
  const form = document.querySelector<HTMLFormElement>("#asset-verification-form")!;
  const manifest = document.querySelector<HTMLInputElement>("#asset-verification-list")!;
  const model = document.querySelector<HTMLInputElement>("#asset-verification-model")!;
  const result = document.querySelector("#asset-verification-result")!;
  let revision = 0;
  let verified: { metadata: LocalAssetMetadata; bytes: ArrayBuffer } | null = null;
  const placement = document.querySelector<HTMLFormElement>("#reference-placement-form")!;
  const placeButton = document.querySelector<HTMLButtonElement>("#reference-place")!;
  const remove = document.querySelector<HTMLButtonElement>("#reference-remove")!;
  const placementStatus = document.querySelector("#reference-placement-status")!;
  const x = document.querySelector<HTMLInputElement>("#reference-x")!;
  const z = document.querySelector<HTMLInputElement>("#reference-z")!;
  function invalidateVerification() { verified = null; placeButton.disabled = true; }

  function reset() {
    revision++;
    invalidateVerification();
    result.textContent = "等待核验所选文件。";
  }
  manifest.addEventListener("change", reset);
  model.addEventListener("change", reset);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const mine = ++revision;
    invalidateVerification();
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
      verified = { metadata: found, bytes };
      placeButton.disabled = false;
      const unit = found.sourceUnit === "mm" ? "毫米" : "米";
      result.textContent = `核验通过：${found.name}，源单位 ${unit}，${found.metrics.triangles} 个三角面；SHA-256 与清单一致。`;
    } catch (failure) {
      if (mine === revision) result.textContent = "核验未通过：" + (failure instanceof Error ? failure.message : "无法读取本地文件。");
    }
  });
  placement.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!verified) return;
    try {
      place(verified.metadata, verified.bytes, [x.valueAsNumber, z.valueAsNumber]);
      placementStatus.textContent = `已放置临时参考：${verified.metadata.name}，不会随场景导出。`;
      remove.disabled = false;
    } catch (failure) {
      placementStatus.textContent = failure instanceof Error ? failure.message : "无法放置参考模型。";
    }
  });
  function clear() {
    clearReference();
    remove.disabled = true;
    placementStatus.textContent = "当前场景没有临时参考。";
  }
  remove.addEventListener("click", clear);
  window.addEventListener("pagehide", () => { revision++; });
  return { clear };
}
