import { createSimulationController, type SimulationWorkerPort } from "../sim/workerBridge.ts";

const form = document.querySelector<HTMLFormElement>("#simulation-form")!;
const batch = document.querySelector<HTMLInputElement>("#simulation-batch")!;
const status = document.querySelector<HTMLParagraphElement>("#simulation-status")!;
const button = document.querySelector<HTMLButtonElement>("#simulation-run")!;
const output = document.querySelector<HTMLDivElement>("#simulation-output")!;
const stations = document.querySelector<HTMLUListElement>("#sim-stations")!;
const text = (id: string, value: string) => { document.getElementById(id)!.textContent = value; };
const number = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 });
const controller = createSimulationController(() => {
  const native = new Worker(new URL("../sim/preview.worker.ts", import.meta.url), { type: "module" });
  const port: SimulationWorkerPort = {
    onmessage: null, onerror: null, onmessageerror: null,
    postMessage: (message) => native.postMessage(message),
    terminate: () => native.terminate(),
  };
  native.onmessage = (event) => port.onmessage?.(event);
  native.onerror = (event) => { event.preventDefault(); port.onerror?.(); };
  native.onmessageerror = () => port.onmessageerror?.();
  return port;
}, (state) => {
  status.textContent = state.status === "running" ? "正在运行仿真…"
    : state.status === "failed" ? state.message : "仿真完成。";
  button.textContent = state.status === "running" ? "重新运行仿真" : "运行仿真";
  output.hidden = state.status !== "done";
  if (state.status !== "done") return;
  const s = state.summary;
  const bottleneck = s.nodes.find((node) => node.id === s.bottleneckNodeId);
  text("sim-completed", number.format(s.completedBatch) + " 件");
  text("sim-makespan", number.format(s.makespanSec) + " 秒");
  text("sim-bottleneck", bottleneck?.name + " · " + number.format(s.bottleneckTaktSec) + " 秒/件");
  text("sim-theoretical", number.format(s.theoreticalUph) + " 件/小时");
  text("sim-effective", number.format(s.effectiveUph) + " 件/小时");
  stations.replaceChildren(...s.nodes.map((node) => {
    const row = document.createElement("li");
    row.textContent = node.name + "：利用率 " + number.format(node.utilization * 100) + "%，堵料 "
      + number.format(node.blockedTimeSec) + " 秒，峰值排队 " + number.format(node.peakWaiting) + " 件。";
    return row;
  }));
});
form.addEventListener("submit", (event) => {
  event.preventDefault();
  controller.run(batch.valueAsNumber);
});
window.addEventListener("pagehide", (event) => { if (!event.persisted) controller.dispose(); });
