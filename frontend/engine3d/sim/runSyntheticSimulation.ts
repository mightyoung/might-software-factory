import { runDiscreteEventSimulation } from "./DesSimulationEngine.ts";
import { SYNTHETIC_STATIONS, type SimulationRequest, type SimulationResult } from "./workerProtocol.ts";

export function runSyntheticSimulation(request: SimulationRequest): SimulationResult {
  return {
    kind: "result", requestId: request.requestId,
    summary: runDiscreteEventSimulation(SYNTHETIC_STATIONS, request.batchSize),
  };
}
