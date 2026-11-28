import type { SimulationSummary } from "./DesSimulationEngine.ts";
import { parseSimulationSummary, validBatch, wireObject, type SimulationRequest } from "./workerProtocol.ts";

export interface SimulationWorkerPort {
  onmessage: ((event: { data: unknown }) => void) | null;
  onerror: (() => void) | null;
  onmessageerror: (() => void) | null;
  postMessage(request: SimulationRequest): void;
  terminate(): void;
}
export type SimulationState =
  | { status: "running" }
  | { status: "done"; summary: SimulationSummary }
  | { status: "failed"; message: string };

const FAILED = "仿真未完成；你可以修改批量或重新运行。";

export function createSimulationController(
  createWorker: () => SimulationWorkerPort,
  stateChanged: (state: SimulationState) => void,
) {
  let active: { port: SimulationWorkerPort; timer: ReturnType<typeof setTimeout> } | null = null;
  let nextId = 0;
  let disposed = false;

  function stop() {
    if (!active) return;
    const { port, timer } = active;
    active = null;
    clearTimeout(timer);
    port.onmessage = null;
    port.onerror = null;
    port.onmessageerror = null;
    port.terminate();
  }
  return {
    run(batchSize: number) {
      if (disposed) return;
      stop();
      if (!validBatch(batchSize)) {
        stateChanged({ status: "failed", message: "批量必须为 1 到 10000 的整数。" });
        return;
      }
      const requestId = ++nextId;
      const fail = () => {
        if (disposed || requestId !== nextId || !active) return;
        stop();
        stateChanged({ status: "failed", message: FAILED });
      };
      try {
        const port = createWorker();
        active = { port, timer: setTimeout(fail, 15_000) };
        port.onerror = fail;
        port.onmessageerror = fail;
        port.onmessage = ({ data }) => {
          if (disposed || requestId !== nextId || active?.port !== port) return;
          const reply = wireObject(data);
          if (!reply || typeof reply.requestId !== "number") { fail(); return; }
          if (reply.requestId !== requestId) return;
          const summary = reply.kind === "result" ? parseSimulationSummary(reply.summary, batchSize) : null;
          if (!summary) { fail(); return; }
          stop();
          stateChanged({ status: "done", summary });
        };
        stateChanged({ status: "running" });
        port.postMessage({ kind: "run", requestId, batchSize });
      } catch {
        if (requestId === nextId) {
          stop();
          if (!disposed) stateChanged({ status: "failed", message: FAILED });
        }
      }
    },
    dispose() { disposed = true; stop(); },
  };
}
