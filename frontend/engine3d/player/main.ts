import { CameraTour } from "../core/CameraTour.ts";
import { mountEngine } from "../core/EngineKernel.ts";
import { hudKeys, hudLabels, type TourPoint } from "../schema/studioSnapshot.ts";
import { MAX_PLAYER_BYTES, parsePlayerSnapshot } from "../schema/playerSnapshot.ts";

const fileInput = document.querySelector<HTMLInputElement>("#play-file")!;
const canvas = document.querySelector<HTMLCanvasElement>("#play-canvas")!;
const list = document.querySelector<HTMLUListElement>("#play-list")!;
const status = document.querySelector<HTMLParagraphElement>("#play-status")!;
const error = document.querySelector<HTMLParagraphElement>("#play-error")!;
const metrics = document.querySelector<HTMLDListElement>("#play-metrics")!;
const hud = document.querySelector<HTMLElement>("#play-hud")!;
const graphics = document.querySelector<HTMLParagraphElement>("#play-graphics")!;
const selection = document.querySelector<HTMLParagraphElement>("#play-selection")!;
const labels = { normal: "正常", warning: "预警", alarm: "告警" };
let engine: ReturnType<typeof mountEngine>;
let revision = 0;
const tour = new CameraTour();
const play = document.querySelector<HTMLButtonElement>("#tour-play")!;
const pause = document.querySelector<HTMLButtonElement>("#tour-pause")!;
const tourStatus = document.querySelector<HTMLParagraphElement>("#tour-status")!;
let authored = new Map<string, TourPoint>();
let labelsById = new Map<string, string>();
let timer = 0;
function highlight(id: string) {
  engine?.focus(id);
  selection.hidden = false;
  selection.textContent = `已选：${labelsById.get(id)}。`;
  for (const button of list.querySelectorAll<HTMLButtonElement>("button"))
    button.setAttribute("aria-pressed", String(button.dataset.id === id));
}
function stopTour(reason = "") {
  if (timer) window.clearTimeout(timer);
  timer = 0;
  tour.pause();
  pause.disabled = true;
  play.disabled = labelsById.size === 0;
  if (reason) tourStatus.textContent = reason;
}
play.addEventListener("click", () => {
  stopTour();
  const first = tour.play();
  if (!first) return;
  play.disabled = true;
  pause.disabled = false;
  tourStatus.textContent = "正在按顺序巡检设备。";
  function step(id: string) {
    const point = authored.get(id);
    highlight(id);
    if (point) tourStatus.textContent = `正在放映机位：${point.label}。`;
    timer = window.setTimeout(() => {
      const next = tour.advance();
      if (next) step(next);
      else stopTour("设备巡检已完成。");
    }, (point?.dwellSeconds ?? 3) * 1000);
  }
  step(first);
});
pause.addEventListener("click", () => { stopTour("设备巡检已暂停。"); play.focus(); });
document.addEventListener("visibilitychange", () => {
  if (document.hidden && tour.playing) stopTour("设备巡检因页面不可见而暂停。");
});

fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  fileInput.value = "";
  if (!file) return;
  const mine = ++revision;
  try {
    if (!file.name.toLowerCase().endsWith(".json") || file.size > MAX_PLAYER_BYTES)
      throw new Error("请选择大小符合要求的 JSON 放映文件。");
    const { scene, floorplan, cameraTour, hudWidgets } = await parsePlayerSnapshot(await file.text());
    if (mine !== revision) return;
    if (engine) engine.replaceScene(scene);
    else engine = mountEngine(canvas, scene, (state) => {
      canvas.hidden = state !== "ready";
      graphics.textContent = state === "ready" ? "三维放映已就绪。"
        : state === "lost" ? "三维暂时不可用，恢复前可用二维列表。" : "没有 WebGL 2，可使用二维列表。";
    });
    engine?.hideModel();
    engine?.replaceFloorplan(floorplan);
    labelsById = new Map(scene.devices.map(({ id, label }) => [id, label]));
    authored = new Map((cameraTour ?? []).map((point) => [point.deviceId, point]));
    tour.replace(cameraTour?.length ? cameraTour.map(({ deviceId }) => deviceId) : scene.devices.map(({ id }) => id));
    stopTour("已准备好设备巡检。");
    status.textContent = `正在放映 ${scene.screenId}：${scene.devices.length} 个设备，${floorplan.walls.length} 条墙，${floorplan.zones.length} 个二维区域。`;
    list.replaceChildren(...scene.devices.map(({ id, label, state }) => {
      const row = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = `${label} · ${labels[state]}`;
      button.dataset.id = id;
      button.setAttribute("aria-pressed", "false");
      button.addEventListener("click", () => {
        const wasPlaying = tour.playing;
        tour.select(id);
        if (wasPlaying) stopTour("设备巡检已因手动选择暂停。");
        highlight(id);
      });
      row.append(button);
      return row;
    }));
    selection.hidden = true;
    const values = { devices: scene.devices.length, alarms: scene.devices.filter((item) => item.state === "alarm").length,
      walls: floorplan.walls.length, zones: floorplan.zones.length };
    const visible = hudWidgets ?? hudKeys;
    metrics.replaceChildren(...visible.map((key) => {
      const item = document.createElement("div"), label = document.createElement("dt"), number = document.createElement("dd");
      label.textContent = hudLabels[key];
      number.textContent = String(values[key]);
      item.append(label, number);
      return item;
    }));
    hud.hidden = visible.length === 0;
    error.hidden = true;
  } catch (cause) {
    if (mine !== revision) return;
    error.textContent = cause instanceof Error ? cause.message : "无法读取放映文件。";
    error.hidden = false;
  }
});
window.addEventListener("pagehide", (event) => { stopTour(); if (!event.persisted) { revision++; engine?.dispose(); } });
