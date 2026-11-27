import { mountEngine, type RenderStatus } from "../core/EngineKernel.ts";
import { SceneDocumentSchema } from "../schema/scene.ts";

let sample = SceneDocumentSchema.parse({
  schemaVersion: 1, screenId: "sample-workshop",
  devices: [
    { id: "pump-01", label: "循环泵 01", primitive: "cylinder", position: [-4, 0, 1], size: [2.4, 2.1, 2.4], state: "normal" },
    { id: "press-02", label: "成型机 02", primitive: "box", position: [1, 0, -2], size: [3, 3, 2], state: "warning" },
    { id: "fan-03", label: "排风装置 03", primitive: "box", position: [5, 0, 1], size: [2, 2, 2.6], state: "alarm" },
    { id: "conveyor-04", label: "传送带 04", position: [-3, 0, 6], state: "normal",
      equipment: { kind: "CONVEYOR", dimensions: { length: 4, width: 1, height: 0.9 } } },
    { id: "corridor-05", label: "廊道 05", position: [0, 0, -7], state: "normal",
      equipment: { kind: "CORRIDOR", dimensions: { length: 8, width: 1.8, height: 1.2 } } },
    { id: "robot-06", label: "机械臂 06", position: [5, 0, -4], state: "warning",
      equipment: { kind: "ROBOT_ARM", dimensions: { length: 2, width: 1.8, height: 2.2 } } },
    { id: "spiral-07", label: "螺旋传送机 07", position: [7, 0, 5], state: "normal",
      equipment: { kind: "SPIRAL_CONVEYOR", dimensions: { length: 3, width: 3, height: 3.3 } } },
  ],
});

const canvas = document.querySelector<HTMLCanvasElement>("#viewport")!;
const status = document.querySelector<HTMLParagraphElement>("#status")!;
const selection = document.querySelector<HTMLParagraphElement>("#selection")!;
const overview = document.querySelector<HTMLButtonElement>("#overview")!;
const devices = document.querySelector<HTMLUListElement>("#devices")!;
const labels = { normal: "正常", warning: "预警", alarm: "告警" };
const messages: Record<RenderStatus, string> = {
  ready: "三维视口已就绪。",
  lost: "三维视口暂时中断。你仍可使用二维设备列表。",
  unavailable: "此设备无法使用 WebGL 2。请使用二维设备列表。",
};
const engine = mountEngine(canvas, sample, (state) => {
  status.textContent = messages[state];
  overview.disabled = state !== "ready";
  // Do not remove a temporarily lost canvas while the browser is restoring it.
  canvas.hidden = state === "unavailable";
});

const buttons = new Map<string, HTMLButtonElement>();
function selectDevice(device?: typeof sample.devices[number]) {
  selection.textContent = device ? `已选：${device.label} · ${labels[device.state]}` : "未选中设备。";
  for (const [id, button] of buttons) button.setAttribute("aria-pressed", String(id === device?.id));
  engine?.focus(device?.id);
}
for (const device of sample.devices) {
  const row = document.createElement("li");
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = `${device.label} · ${labels[device.state]}`;
  button.setAttribute("aria-pressed", "false");
  button.addEventListener("click", () => selectDevice(device));
  buttons.set(device.id, button);
  row.append(button);
  devices.append(row);
}
overview.addEventListener("click", () => selectDevice());
const lengthSelect = document.querySelector<HTMLSelectElement>("#conveyor-length")!;
const rebuild = document.querySelector<HTMLParagraphElement>("#conveyor-rebuild")!;
lengthSelect.addEventListener("change", () => {
  const length = Number(lengthSelect.value);
  if (length !== 4 && length !== 8) return;
  sample = SceneDocumentSchema.parse({
    ...sample,
    devices: sample.devices.map((device) => "equipment" in device && device.id === "conveyor-04"
      ? { ...device, equipment: { ...device.equipment, dimensions: { ...device.equipment.dimensions, length } } }
      : device),
  });
  engine?.replaceScene(sample);
  rebuild.textContent = `当前长度：${length} 米。`;
});
window.addEventListener("pagehide", (event) => { if (!event.persisted) engine?.dispose(); });
