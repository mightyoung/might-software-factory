export interface SimNodeInput {
  id: string;
  name: string;
  cycleTimeSec: number;
  transferTimeSec: number;
  /** Waiting slots before this station, excluding its own machine. Zero means direct handoff. */
  bufferCapacity: number;
  downstreamIds: readonly string[];
}

export interface NodeSimMetrics {
  id: string;
  name: string;
  effectiveStepSec: number;
  completedCount: number;
  busyTimeSec: number;
  blockedTimeSec: number;
  utilization: number;
  peakWaiting: number;
  isBottleneck: boolean;
}

export interface SimulationSummary {
  totalBatch: number;
  completedBatch: number;
  makespanSec: number;
  bottleneckNodeId: string;
  bottleneckTaktSec: number;
  theoreticalUph: number;
  effectiveUph: number;
  avgLeadTimeSec: number;
  nodes: NodeSimMetrics[];
}

const MAX_BATCH = 10_000;
const MAX_STATIONS = 64;
const MAX_SECONDS = 86_400;
const MS_PER_HOUR = 3_600_000;
const ID = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/;

function milliseconds(value: number, minimum: number): number {
  const ticks = Math.round(value * 1000);
  if (!Number.isFinite(value) || value < minimum || value > MAX_SECONDS ||
      Math.abs(ticks - value * 1000) > 1e-7) {
    throw new RangeError("Station times must be in milliseconds within the supported range");
  }
  return ticks;
}

function serialLine(nodes: readonly SimNodeInput[]) {
  if (!Array.isArray(nodes) || nodes.length < 1 || nodes.length > MAX_STATIONS) {
    throw new RangeError("Expected 1–64 stations");
  }
  const byId = new Map<string, { node: SimNodeInput; service: number }>();
  const preceded = new Set<string>();
  for (const node of nodes) {
    if (!node || typeof node.id !== "string" || !ID.test(node.id) || byId.has(node.id) ||
        typeof node.name !== "string" || !node.name.trim() || node.name.length > 80) {
      throw new RangeError("Expected unique station IDs and valid station names");
    }
    if (!Array.isArray(node.downstreamIds) || node.downstreamIds.length > 1 ||
        !Number.isInteger(node.bufferCapacity) || node.bufferCapacity < 0 || node.bufferCapacity > MAX_BATCH) {
      throw new RangeError("Expected serial topology and bounded waiting buffers");
    }
    byId.set(node.id, { node, service: milliseconds(node.cycleTimeSec, 0.001) + milliseconds(node.transferTimeSec, 0) });
  }
  for (const node of nodes) {
    if (!node.downstreamIds.length) continue;
    const next = node.downstreamIds[0];
    if (!byId.has(next) || preceded.has(next)) {
      throw new RangeError("Merge or unknown downstream station is not supported");
    }
    preceded.add(next);
  }
  const entries = nodes.filter(({ id }) => !preceded.has(id));
  if (entries.length !== 1) throw new RangeError("Expected exactly one entry station");
  const line = [];
  let current = byId.get(entries[0].id);
  while (current) {
    line.push(current);
    if (line.length > nodes.length) throw new RangeError("Cyclic serial line");
    current = byId.get(current.node.downstreamIds[0]);
  }
  if (line.length !== nodes.length) throw new RangeError("Disconnected or cyclic station");
  return line;
}

/**
 * Deterministic event times for an all-at-time-zero batch on a single FIFO line.
 * A station does its cycle and serial transfer before releasing its unit. If
 * all downstream slots are occupied, the finished unit keeps the station blocked.
 * No randomness, wall time, network, or browser dependency.
 */
export function runDiscreteEventSimulation(
  nodes: readonly SimNodeInput[],
  batchSize = 100,
): SimulationSummary {
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > MAX_BATCH) {
    throw new RangeError("Expected an integer batch size between 1 and 10,000");
  }
  const line = serialLine(nodes);
  const starts = line.map(() => new Float64Array(batchSize));
  const departures = line.map(() => new Float64Array(batchSize));
  const blocked = line.map(() => 0);
  const peak = line.map(() => 0);
  const servedOnArrival = line.map(() => 0);
  let totalLeadTime = 0;
  const last = line.length - 1;
  for (let unit = 0; unit < batchSize; unit++) {
    for (let station = 0; station < line.length; station++) {
      const arrival = station === 0 ? 0 : departures[station - 1][unit];
      const start = Math.max(arrival, unit === 0 ? 0 : departures[station][unit - 1]);
      starts[station][unit] = start;
      const finish = start + line[station].service;
      let departure = finish;
      if (station < last) {
        const capacity = line[station + 1].node.bufferCapacity;
        if (capacity === 0 && unit > 0) {
          departure = Math.max(finish, departures[station + 1][unit - 1]);
        } else if (capacity > 0 && unit >= capacity) {
          departure = Math.max(finish, starts[station + 1][unit - capacity]);
        }
      }
      departures[station][unit] = departure;
      blocked[station] += departure - finish;

      if (station > 0) {
        let served = servedOnArrival[station];
        while (served <= unit && starts[station][served] <= arrival) served++;
        servedOnArrival[station] = served;
        peak[station] = Math.max(peak[station], unit + 1 - served);
      }
    }
    totalLeadTime += departures[last][unit];
  }
  const makespan = departures[last][batchSize - 1];
  let bottleneck = 0;
  for (let station = 1; station < line.length; station++) {
    if (line[station].service > line[bottleneck].service) bottleneck = station;
  }
  const bottleneckStep = line[bottleneck].service;
  return {
    totalBatch: batchSize,
    completedBatch: batchSize,
    makespanSec: makespan / 1000,
    bottleneckNodeId: line[bottleneck].node.id,
    bottleneckTaktSec: bottleneckStep / 1000,
    theoreticalUph: MS_PER_HOUR / bottleneckStep,
    effectiveUph: (batchSize * MS_PER_HOUR) / makespan,
    avgLeadTimeSec: totalLeadTime / batchSize / 1000,
    nodes: line.map(({ node, service }, index) => ({
      id: node.id,
      name: node.name,
      effectiveStepSec: service / 1000,
      completedCount: batchSize,
      busyTimeSec: service * batchSize / 1000,
      blockedTimeSec: blocked[index] / 1000,
      utilization: service * batchSize / makespan,
      peakWaiting: peak[index],
      isBottleneck: index === bottleneck,
    })),
  };
}
