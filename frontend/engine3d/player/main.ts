import { mountEngine } from "../core/EngineKernel.ts";
import { MAX_PLAYER_BYTES, parsePlayerSnapshot } from "../schema/playerSnapshot.ts";

const fileInput = document.querySelector<HTMLInputElement>("#play-file")!;
const canvas = document.querySelector<HTMLCanvasElement>("#play-canvas")!;
const list = document.querySelector<HTMLUListElement>("#play-list")!;
const status = document.querySelector<HTMLParagraphElement>("#play-status")!;
const error = document.querySelector<HTMLParagraphElement>("#play-error")!;
const graphics = document.querySelector<HTMLParagraphElement>("#play-graphics")!;
const selection = document.querySelector<HTMLParagraphElement>("#play-selection")!;
const labels = { normal: "正常", warning: "预警", alarm: "告警" };
let engine: ReturnType<typeof mountEngine>;
let revision = 0;

fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  fileInput.value = "";
  if (!file) return;
  const mine = ++revision;
  try {
    if (!file.name.toLowerCase().endsWith(".json") || file.size > MAX_PLAYER_BYTES)
      throw new Error("请选择大小符合要求的 JSON 放映文件。");
    const { scene, floorplan } = await parsePlayerSnapshot(await file.text());
    if (mine !== revision) return;
    if (engine) engine.replaceScene(scene);
    else engine = mountEngine(canvas, scene, (state) => {
      canvas.hidden = state !== "ready";
      graphics.textContent = state === "ready" ? "三维放映已就绪。"
        : state === "lost" ? "三维暂时不可用，恢复前可用二维列表。" : "没有 WebGL 2，可使用二维列表。";
    });
    engine?.hideModel();
    engine?.replaceFloorplan(floorplan);
    status.textContent = `正在放映 ${scene.screenId}：${scene.devices.length} 个设备，${floorplan.walls.length} 条墙，${floorplan.zones.length} 个二维区域。`;
    list.replaceChildren(...scene.devices.map(({ id, label, state }) => {
      const row = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = `${label} · ${labels[state]}`;
      button.addEventListener("click", () => {
        engine?.focus(id);
        selection.hidden = false;
        selection.textContent = `已选：${label}。`;
      });
      row.append(button);
      return row;
    }));
    selection.hidden = true;
    error.hidden = true;
  } catch (cause) {
    if (mine !== revision) return;
    error.textContent = cause instanceof Error ? cause.message : "无法读取放映文件。";
    error.hidden = false;
  }
});
window.addEventListener("pagehide", (event) => { if (!event.persisted) { revision++; engine?.dispose(); } });
