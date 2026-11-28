import type { SimulationSummary } from "./DesSimulationEngine.ts";

export const SIMULATION_MAX_BATCH = 10_000;
export const SYNTHETIC_STATIONS = [
  { id: "feed", name: "上料", cycleTimeSec: 1.5, transferTimeSec: 0.5, bufferCapacity: 0, downstreamIds: ["process"] },
  { id: "process", name: "加工", cycleTimeSec: 3, transferTimeSec: 0, bufferCapacity: 1, downstreamIds: ["inspect"] },
  { id: "inspect", name: "检验", cycleTimeSec: 1, transferTimeSec: 0, bufferCapacity: 1, downstreamIds: [] },
] as const;

export type SimulationRequest = { kind: "run"; requestId: number; batchSize: number };
export type SimulationResult = { kind: "result"; requestId: number; summary: SimulationSummary };

export function wireObject(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

export function validBatch(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= SIMULATION_MAX_BATCH;
}

export function parseSimulationRequest(value: unknown): SimulationRequest | null {
  const data = wireObject(value);
  if (data?.kind !== "run" || Object.keys(data).length !== 3 ||
      typeof data.requestId !== "number" || !Number.isSafeInteger(data.requestId) ||
      data.requestId < 1 || !validBatch(data.batchSize)) return null;
  return { kind: "run", requestId: data.requestId, batchSize: data.batchSize };
}

const metric = (value: unknown, limit: number) =>
  typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= limit;

export function parseSimulationSummary(value: unknown, batchSize: number): SimulationSummary | null {
  const data = wireObject(value);
  if (!data || data.totalBatch !== batchSize || data.completedBatch !== batchSize ||
      !metric(data.makespanSec, 100_000) || !metric(data.avgLeadTimeSec, 100_000) ||
      !metric(data.bottleneckTaktSec, 10) || !metric(data.theoreticalUph, 3600) || !metric(data.effectiveUph, 3600) ||
      !SYNTHETIC_STATIONS.some(({ id }) => id === data.bottleneckNodeId) ||
      !Array.isArray(data.nodes) || data.nodes.length !== SYNTHETIC_STATIONS.length) return null;
  for (const [i, item] of data.nodes.entries()) {
    const node = wireObject(item);
    const fixture = SYNTHETIC_STATIONS[i];
    if (!node || node.id !== fixture.id || node.name !== fixture.name || node.completedCount !== batchSize ||
        !metric(node.effectiveStepSec, 10) || !metric(node.busyTimeSec, 100_000) ||
        !metric(node.blockedTimeSec, 100_000) || !metric(node.utilization, 1) ||
        !metric(node.peakWaiting, fixture.bufferCapacity) || typeof node.isBottleneck !== "boolean") return null;
  }
  return data as unknown as SimulationSummary;
}
