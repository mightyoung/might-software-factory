import { mountTourEditor } from "./tourEditor.ts";
import { createPlayerSnapshot } from "../schema/playerSnapshot.ts";
import { MAX_STUDIO_BYTES, parseStudioSnapshot, serializeStudioSnapshot, hudKeys, type HudKey } from "../schema/studioSnapshot.ts";
import { mountFloorplanEditor } from "./floorplanEditor.ts";
import { mountResizeGizmo } from "./resizeGizmo.ts";
import { resizeEquipment, type ResizeFace } from "../core/equipmentResize.ts";
import { inspectPreviewUnits, type SourceUnit } from "../core/modelUnits.ts";
import { LocalAssetBank } from "../core/LocalAssetBank.ts";
import { MAX_GLB_BYTES, openStaticGlb, type StaticGlb } from "../core/glbIntake.ts";
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
let updateGizmo = () => {};
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
const wallStatus = document.querySelector<HTMLParagraphElement>("#wall-preview-status")!;
let wallCount = 0;
function displayWallStatus() {
  wallStatus.hidden = !engine || !wallCount || Boolean(inspection);
  wallStatus.textContent = wallCount ? `三维墙体 ${wallCount} 条，高 3 米，厚 0.2 米。` : "";
}
const modelClose = document.querySelector<HTMLButtonElement>("#close-model")!;
const modelUnit = document.querySelector<HTMLSelectElement>("#model-unit")!;
const unitWarning = document.querySelector<HTMLParagraphElement>("#unit-warning")!;
const resizeTool = document.querySelector<HTMLFieldSetElement>("#resize-tool")!;
const resizeResult = document.querySelector<HTMLParagraphElement>("#resize-result")!;
const resizeFace = document.querySelector<HTMLSelectElement>("#resize-face")!;
let inspection: { name: string; metrics: StaticGlb["metrics"]; rendered: boolean; source: ArrayBuffer } | null = null;
const bank = new LocalAssetBank();
const bankList = document.querySelector<HTMLUListElement>("#model-bank")!;
const bankInfo = document.querySelector<HTMLParagraphElement>("#model-bank-info")!;
const saveModel = document.querySelector<HTMLButtonElement>("#save-model")!;
function renderBank() {
  bankList.replaceChildren(...bank.list().map((asset) => {
    const row = document.createElement("li");
    const load = document.createElement("button"), remove = document.createElement("button");
    load.type = remove.type = "button";
    load.textContent = `预览 ${asset.name}`;
    remove.textContent = `移除 ${asset.name}`;
    load.addEventListener("click", () => {
      const bytes = bank.read(asset.id);
      if (bytes) { readVersion++; error(); showLocalModel(asset.name, bytes); }
    });
    remove.addEventListener("click", () => {
      bank.remove(asset.id);
      renderBank();
      bankInfo.textContent = `已从会话模型库移除 ${asset.name}。`;
      bankInfo.focus();
    });
    row.append(load, " ", remove);
    return row;
  }));
}
saveModel.addEventListener("click", async () => {
  const current = inspection;
  if (!current || saveModel.disabled) return;
  saveModel.disabled = true;
  try {
    const existing = new Set(bank.list().map((asset) => asset.id));
    const stored = await bank.register(current.name, current.source);
    renderBank();
    bankInfo.textContent = existing.has(stored.id) ? `文件内容相同，沿用 ${stored.name}。`
      : `已留存 ${stored.name}。模型库共 ${bank.list().length} 个。`;
  } catch (cause) {
    bankInfo.textContent = cause instanceof Error ? cause.message : "无法加入会话模型库。";
  } finally {
    saveModel.disabled = false;
  }
});
function showLocalModel(name: string, source: ArrayBuffer) {
  const model = openStaticGlb(source);
  const rendered = engine?.showModel(model);
  if (!rendered) model.dispose();
  inspection = { name, metrics: model.metrics, rendered: Boolean(rendered), source };
  displayInspection();
  displayWallStatus();
  modelClose.hidden = false;
  saveModel.hidden = false;
}
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
function displayResize() {
  const item = selectedParametric();
  resizeTool.hidden = !item;
  if (!item || !("equipment" in item)) return;
  const { length, width, height } = item.equipment.dimensions;
  resizeResult.textContent = `长 ${length} · 宽 ${width} · 高 ${height} 米。`;
}
function adjustSelected(delta: number, face = resizeFace.value as ResizeFace) {
  const item = selectedParametric();
  if (!item || !("equipment" in item)) return;
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
  displayWallStatus();
  modelClose.hidden = true;
  saveModel.hidden = true;
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
    showLocalModel(file.name, bytes);
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
function renderDevices() {
  buttons.clear();
  devices.replaceChildren();
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
}
renderDevices();
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
  displayResize();
});
updateGizmo = mountResizeGizmo(document.querySelector("#resize-gizmo")!, () => {
  const item = selectedParametric();
  return item && "equipment" in item ? engine?.projectedHandles(item.id, item.equipment.dimensions) ?? [] : [];
}, (delta, face, id) => { if (selected === id) adjustSelected(delta, face); });
window.addEventListener("pagehide", (event) => {
  if (!event.persisted) { readVersion++; engine?.dispose(); }
});

const planEditor = mountFloorplanEditor((snapshot) => {
  wallCount = engine?.replaceFloorplan(snapshot) ? snapshot.walls.length : 0;
  displayWallStatus();
});

const hudChecks = Array.from(document.querySelectorAll<HTMLInputElement>("#hud-editor input"));
const selectedHud = () => {
  const keys = hudChecks.filter((input) => input.checked).map((input) => input.value as HudKey);
  return keys.length === hudKeys.length ? undefined : keys;
};
function restoreHud(keys: readonly HudKey[]) {
  for (const input of hudChecks) input.checked = keys.includes(input.value as HudKey);
}
const tourEditor = mountTourEditor();
tourEditor.setScene(sample);
const importDraft = document.querySelector<HTMLInputElement>("#snapshot-import")!;
const draftStatus = document.querySelector<HTMLParagraphElement>("#snapshot-status")!;
const draftError = document.querySelector<HTMLParagraphElement>("#snapshot-error")!;
function snapshotError(message = "") {
  draftError.textContent = message;
  draftError.hidden = !message;
}
function downloadSnapshot(raw: string, filename: string) {
  const url = URL.createObjectURL(new Blob([raw], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
document.querySelector("#player-export")!.addEventListener("click", async () => {
  try {
    downloadSnapshot(await createPlayerSnapshot(serializeStudioSnapshot(sample, planEditor.snapshot(), tourEditor.snapshot(), selectedHud())), "might-player-v1.json");
    snapshotError();
    draftStatus.textContent = "离线放映文件已交给浏览器下载。";
  } catch (cause) { snapshotError(cause instanceof Error ? cause.message : "放映文件生成失败。"); }
});
document.querySelector("#snapshot-export")!.addEventListener("click", () => {
  try {
    downloadSnapshot(serializeStudioSnapshot(sample, planEditor.snapshot(), tourEditor.snapshot(), selectedHud()), "might-scene-draft.json");
    snapshotError();
    draftStatus.textContent = "场景草稿已交给浏览器下载。";
  } catch (cause) {
    snapshotError(cause instanceof Error ? cause.message : "场景草稿导出失败。");
  }
});
let snapshotRevision = 0;
importDraft.addEventListener("change", async () => {
  const file = importDraft.files?.[0];
  importDraft.value = "";
  if (!file) return;
  const mine = ++snapshotRevision;
  try {
    if (!file.name.toLowerCase().endsWith(".json") || file.size > MAX_STUDIO_BYTES)
      throw new Error("请选择不超过 256 KiB 的 JSON 场景草稿。");
    const raw = await file.text();
    if (mine !== snapshotRevision) return;
    const next = parseStudioSnapshot(raw);
    // Validate all shapes before mutating either visible editor.
    sample = next.scene;
    renderDevices();
    selectDevice();
    engine?.replaceScene(sample);
    planEditor.restore(next.floorplan);
    tourEditor.setScene(sample, next.cameraTour ?? []);
    restoreHud(next.hudWidgets ?? hudKeys);
    const conveyor = sample.devices.find((device) => device.id === "conveyor-04" && "equipment" in device);
    document.querySelector<HTMLElement>("#conveyor-tool")!.hidden = !conveyor;
    if (conveyor && "equipment" in conveyor) {
      const { length } = conveyor.equipment.dimensions;
      lengthSelect.value = String(length);
      rebuild.textContent = `当前长度：${length} 米。`;
    }
    snapshotError();
    draftStatus.textContent = `已读取 ${sample.screenId}，共 ${sample.devices.length} 个设备。`;
  } catch (cause) {
    if (mine === snapshotRevision) snapshotError(cause instanceof Error ? cause.message : "场景草稿读取失败。");
  }
});
