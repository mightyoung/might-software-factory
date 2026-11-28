import type { PrefabVersion } from "../core/prefabBank.ts";
import type { FloorMetres, PrefabPlacement } from "../core/prefabPlacement.ts";
import { selectedPrefabVersion } from "./prefabEditor.ts";

const field = (axis: string) => document.querySelector<HTMLInputElement>("#prefab-place-" + axis)!;

export function mountPrefabPlacement(place: (version: PrefabVersion, metres: FloorMetres) => PrefabPlacement) {
  const placementForm = document.querySelector<HTMLFormElement>("#prefab-placement-form")!;
  const placementStatus = document.getElementById("prefab-placement-status")!;
  const placementError = document.getElementById("prefab-placement-error")!;
  placementForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const selectedVersion = selectedPrefabVersion();
    if (!selectedVersion) return;
    try {
      const placed = place(selectedVersion, [field("x").valueAsNumber, field("z").valueAsNumber]);
      placementError.hidden = true;
      placementStatus.textContent = `已放置 ${placed.prefabId} · v${placed.version}，坐标 X ${placed.metres[0]}、Z ${placed.metres[1]} 米。`;
    } catch (failure) {
      placementError.textContent = failure instanceof Error ? failure.message : "无法放置组件。";
      placementError.hidden = false;
    }
  });
}
