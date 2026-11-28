import { parseSimulationRequest } from "./workerProtocol.ts";
import { runSyntheticSimulation } from "./runSyntheticSimulation.ts";

self.addEventListener("message", (event: MessageEvent<unknown>) => {
  const request = parseSimulationRequest(event.data);
  if (!request) return;
  try {
    self.postMessage(runSyntheticSimulation(request));
  } catch {
    self.postMessage({ kind: "error", requestId: request.requestId });
  }
});
