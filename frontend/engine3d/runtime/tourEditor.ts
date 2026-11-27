import type { SceneDocument } from "../schema/scene.ts";
import type { TourPoint } from "../schema/studioSnapshot.ts";

export function mountTourEditor() {
  const list = document.querySelector<HTMLOListElement>("#tour-edit-list")!;
  const status = document.querySelector<HTMLParagraphElement>("#tour-edit-status")!;
  const select = document.querySelector<HTMLSelectElement>("#tour-device")!;
  const name = document.querySelector<HTMLInputElement>("#tour-name")!;
  const dwell = document.querySelector<HTMLInputElement>("#tour-seconds")!;
  let points: TourPoint[] = [];
  function render(message = "") {
    list.replaceChildren(...points.map((point, index) => {
      const row = document.createElement("li");
      row.textContent = `${index + 1}. ${point.label} · ${point.dwellSeconds} 秒 `;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = `移除 ${point.label}`;
      remove.onclick = () => { points = points.filter((p) => p.deviceId !== point.deviceId); render("已移除机位。"); select.focus(); };
      row.append(remove);
      return row;
    }));
    status.textContent = message;
  }
  document.querySelector("#tour-editor")!.addEventListener("submit", (event) => {
    event.preventDefault();
    const deviceId = select.value, label = name.value.trim(), dwellSeconds = dwell.valueAsNumber;
    if (!deviceId || !label || label.length > 60 || !Number.isInteger(dwellSeconds) || dwellSeconds < 1 || dwellSeconds > 30) {
      render("请选择设备、输入机位名称，并填写 1–30 秒的整数停留时间。");
      return;
    }
    if (points.length >= 20 || points.some((p) => p.deviceId === deviceId)) {
      render("每个设备只能添加一个机位，最多 20 个。");
      return;
    }
    points.push({ deviceId, label, dwellSeconds });
    render("已添加机位。");
  });
  return {
    snapshot: () => structuredClone(points),
    setScene(doc: SceneDocument, next?: readonly TourPoint[]) {
      const valid = new Set(doc.devices.map(({ id }) => id));
      const selected = select.value;
      const current = next ? [...next] : points.filter((point) => valid.has(point.deviceId));
      points = structuredClone(current);
      select.replaceChildren(...doc.devices.map(({ id, label }) => new Option(label, id)));
      if (valid.has(selected)) select.value = selected;
      render();
    },
  };
}
