import { mountEngine, type RenderStatus } from "../core/EngineKernel.ts";
import { SceneDocumentSchema } from "../schema/scene.ts";

const sample = SceneDocumentSchema.parse({
  schemaVersion: 1, screenId: "sample-workshop",
  devices: [
    { id: "pump-01", label: "循环泵 01", primitive: "cylinder", position: [-4, 0, 1], size: [2.4, 2.1, 2.4], state: "normal" },
    { id: "press-02", label: "成型机 02", primitive: "box", position: [1, 0, -2], size: [3, 3, 2], state: "warning" },
    { id: "fan-03", label: "排风装置 03", primitive: "box", position: [5, 0, 1], size: [2, 2, 2.6], state: "alarm" },
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
window.addEventListener("pagehide", (event) => { if (!event.persisted) engine?.dispose(); });
