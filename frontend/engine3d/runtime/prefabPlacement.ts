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

export function mountPrefabRelocation(relocate: (metres: FloorMetres) => PrefabPlacement) {
  const form = document.querySelector<HTMLFormElement>("#prefab-relocate-form")!;
  const x = document.querySelector<HTMLInputElement>("#prefab-relocate-x")!;
  const z = document.querySelector<HTMLInputElement>("#prefab-relocate-z")!;
  const message = document.getElementById("prefab-relocate-message")!;
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (form.hidden) return;
    try {
      const moved = relocate([x.valueAsNumber, z.valueAsNumber]);
      message.textContent = `已移动至 X ${moved.metres[0]}、Z ${moved.metres[1]} 米。`;
    } catch (failure) {
      message.textContent = failure instanceof Error ? failure.message : "无法移动组件。";
    }
  });
  return (placement?: PrefabPlacement) => {
    form.hidden = !placement;
    x.value = placement ? String(placement.metres[0]) : "";
    z.value = placement ? String(placement.metres[1]) : "";
    message.textContent = "";
  };
}

export function mountPrefabRemoval(remove: (pin: PrefabPlacement) => void) {
  const controls = document.getElementById("prefab-remove-controls")!;
  const open = document.getElementById("prefab-remove")!;
  const prompt = document.getElementById("prefab-remove-confirmation")!;
  const target = document.getElementById("prefab-remove-target")!;
  const confirm = document.getElementById("prefab-remove-confirm")!;
  const cancel = document.getElementById("prefab-remove-cancel")!;
  const message = document.getElementById("prefab-remove-message")!;
  let selected: PrefabPlacement | undefined;
  let armed: PrefabPlacement | undefined;
  function reset() {
    armed = undefined;
    prompt.hidden = true;
    open.hidden = false;
    target.textContent = "";
  }
  open.addEventListener("click", () => {
    if (!selected) return;
    armed = selected;
    target.textContent = `从当前草稿移除 ${armed.prefabId} · v${armed.version}（${armed.deviceId}）？不会删除组件库版本。`;
    message.textContent = "";
    open.hidden = true;
    prompt.hidden = false;
    cancel.focus();
  });
  cancel.addEventListener("click", () => {
    reset();
    open.focus();
  });
  confirm.addEventListener("click", () => {
    if (!armed || armed !== selected) return;
    try {
      remove(armed);
      message.textContent = "已从当前草稿移除组件实例。";
    } catch (failure) {
      reset();
      open.focus();
      message.textContent = failure instanceof Error ? failure.message : "无法移除组件实例。";
    }
  });
  return (placement?: PrefabPlacement) => {
    reset();
    selected = placement;
    controls.hidden = !placement;
    message.textContent = "";
  };
}
