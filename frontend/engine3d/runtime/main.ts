import { mountFloorplanEditor } from "./floorplanEditor.ts";
import { mountResizeGizmo } from "./resizeGizmo.ts";
import { resizeEquipment, resizeFaces, type ResizeFace } from "../core/equipmentResize.ts";
import { inspectPreviewUnits, type SourceUnit } from "../core/modelUnits.ts";
import { MAX_GLB_BYTES, openStaticGlb, type StaticGlb } from "../core/glbIntake.ts";
import { mountEngine, type RenderStatus } from "../core/EngineKernel.ts";
import { SceneDocumentSchema, type ParametricEquipment } from "../schema/scene.ts";

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
let updateGizmo = () => {};
let cancelGizmo = () => {};
const engine = mountEngine(canvas, sample, (state) => {
  status.textContent = messages[state];
  overview.disabled = state !== "ready";
  // Do not remove a temporarily lost canvas while the browser is restoring it.
  canvas.hidden = state === "unavailable";
  if (state !== "ready") updateGizmo();
}, () => updateGizmo());

const buttons = new Map<string, HTMLButtonElement>();
let selected: string | undefined;
let readVersion = 0;
const modelInput = document.querySelector<HTMLInputElement>("#local-model")!;
const modelResult = document.querySelector<HTMLParagraphElement>("#model-result")!;
const modelError = document.querySelector<HTMLParagraphElement>("#model-error")!;
const modelClose = document.querySelector<HTMLButtonElement>("#close-model")!;
const modelUnit = document.querySelector<HTMLSelectElement>("#model-unit")!;
const unitWarning = document.querySelector<HTMLParagraphElement>("#unit-warning")!;
const resizeTool = document.querySelector<HTMLFieldSetElement>("#resize-tool")!;
const resizeResult = document.querySelector<HTMLParagraphElement>("#resize-result")!;
const resizeFace = document.querySelector<HTMLSelectElement>("#resize-face")!;
let inspection: { name: string; metrics: StaticGlb["metrics"]; rendered: boolean } | null = null;
function displayInspection() {
  const unit: SourceUnit = modelUnit.value === "mm" ? "mm" : "m";
  if (!inspection) { modelResult.textContent = ""; unitWarning.hidden = true; return; }
  const { name, metrics: { vertices, triangles, dimensions }, rendered } = inspection;
  const report = inspectPreviewUnits(dimensions, unit);
  const sizes = report.meters.map((v) => Number(v.toFixed(6))).join(" × ");
  modelResult.textContent = `${name}：${triangles} 个三角面，${vertices} 个顶点，${sizes} 米。${rendered ? "" : "当前只能查看模型数据。"}`;
  unitWarning.textContent = report.checkUnits ? "最大边超过 500 米。请核对源单位，系统不会自动换算。" : "";
  unitWarning.hidden = !report.checkUnits;
}
modelUnit.addEventListener("change", displayInspection);
const selectedParametric = () => sample.devices.find((item) => item.id === selected && "equipment" in item);
const canResizeFace = (kind: ParametricEquipment["kind"], face: ResizeFace) =>
  face === "+Y" || (resizeFaces.includes(face) && (kind === "CONVEYOR" || kind === "CORRIDOR"));
function displayResize() {
  const item = selectedParametric();
  resizeTool.hidden = !item;
  if (!item || !("equipment" in item)) return;
  for (const option of resizeFace.options) {
    option.disabled = !canResizeFace(item.equipment.kind, option.value as ResizeFace);
  }
  if (!canResizeFace(item.equipment.kind, resizeFace.value as ResizeFace)) resizeFace.value = "+Y";
  const { length, width, height } = item.equipment.dimensions;
  resizeResult.textContent = `长 ${length} · 宽 ${width} · 高 ${height} 米。`;
}
function adjustSelected(delta: number, face = resizeFace.value as ResizeFace) {
  const item = selectedParametric();
  if (!item || !("equipment" in item)) return;
  if (!canResizeFace(item.equipment.kind, face)) { displayResize(); return; }
  const change = resizeEquipment(item.equipment, face, delta);
  const [dx, dy, dz] = change.positionDelta;
  const [x, y, z] = item.position;
  const updated = SceneDocumentSchema.safeParse({
    ...sample, devices: sample.devices.map((device) => device.id === item.id
      ? { ...item, position: [x + dx, y + dy, z + dz],
        equipment: { ...item.equipment, dimensions: change.dimensions } } : device),
  });
  if (!updated.success) { resizeResult.textContent = "尺寸调整会超出场景范围。"; return; }
  sample = updated.data;
  closeModel();
  cancelGizmo();
  engine?.replaceScene(sample);
  engine?.focus(item.id);
  if (item.id === "conveyor-04") { lengthSelect.selectedIndex = change.dimensions.length === 4 ? 0 : change.dimensions.length === 8 ? 1 : -1; rebuild.textContent = `当前长度：${change.dimensions.length} 米。`; }
  displayResize();
}
document.querySelector("#resize-in")!.addEventListener("click", () => adjustSelected(-0.5));
document.querySelector("#resize-out")!.addEventListener("click", () => adjustSelected(+0.5));
function error(message = "") {
  modelError.textContent = message;
  modelError.hidden = !message;
}
function closeModel() {
  readVersion++;
  engine?.hideModel(selected);
  inspection = null;
  displayInspection();
  modelClose.hidden = true;
  error();
}
modelClose.addEventListener("click", () => { closeModel(); modelInput.focus(); });
modelInput.addEventListener("change", async () => {
  const file = modelInput.files?.[0];
  modelInput.value = "";
  if (!file) return;
  const mine = ++readVersion;
  error();
  if (!file.name.toLowerCase().endsWith(".glb") || file.size > MAX_GLB_BYTES) {
    error("请选择不超过 8 MiB 的 .glb 文件。");
    return;
  }
  try {
    const bytes = await file.arrayBuffer();
    if (mine !== readVersion) return;
    const model = openStaticGlb(bytes);
    const rendered = engine?.showModel(model);
    if (!rendered) model.dispose();
    inspection = { name: file.name, metrics: model.metrics, rendered: Boolean(rendered) };
    displayInspection();
    modelClose.hidden = false;
  } catch (cause) {
    if (mine === readVersion) error(cause instanceof Error && cause.message.startsWith("GLB：")
      ? cause.message : "无法读取该本地文件。");
  }
});
function selectDevice(device?: typeof sample.devices[number]) {
  selection.textContent = device ? `已选：${device.label} · ${labels[device.state]}` : "未选中设备。";
  for (const [id, button] of buttons) button.setAttribute("aria-pressed", String(id === device?.id));
  selected = device?.id;
  closeModel();
  displayResize();
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
  cancelGizmo();
  engine?.replaceScene(sample);
  rebuild.textContent = `当前长度：${length} 米。`;
  displayResize();
});
const gizmo = mountResizeGizmo(document.querySelector("#resize-gizmo")!, canvas, () => {
  const item = selectedParametric();
  if (!item || !("equipment" in item)) return [];
  const { kind, dimensions } = item.equipment;
  return engine?.projectedHandles(item.id, dimensions).filter(({ face }) => canResizeFace(kind, face)) ?? [];
}, (delta, face, id) => { if (selected === id) adjustSelected(delta, face); });
updateGizmo = gizmo.update;
cancelGizmo = gizmo.cancel;
window.addEventListener("pagehide", (event) => {
  if (!event.persisted) { readVersion++; engine?.dispose(); }
});

mountFloorplanEditor();
