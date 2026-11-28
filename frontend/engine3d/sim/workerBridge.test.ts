import assert from "node:assert/strict";
import { test } from "node:test";
import { createSimulationController, type SimulationWorkerPort } from "./workerBridge.ts";
import { parseSimulationRequest } from "./workerProtocol.ts";
import { runSyntheticSimulation } from "./runSyntheticSimulation.ts";

function worker() {
  const sent: unknown[] = [];
  let stops = 0;
  const port: SimulationWorkerPort = {
    onmessage: null, onerror: null, onmessageerror: null,
    postMessage: (message) => { sent.push(message); },
    terminate: () => { stops++; },
  };
  return { port, sent, stopCount: () => stops };
}

test("wire validator bounds batches and IDs and ignores extra input", () => {
  const message = { kind: "run", requestId: 1, batchSize: 4 };
  assert.deepEqual(parseSimulationRequest(message), message);
  for (const bad of [{ ...message, batchSize: Infinity }, { ...message, batchSize: 0 },
    { ...message, batchSize: 10001 }, { ...message, batchSize: "4" }, { ...message, requestId: 1.5 },
    { ...message, unexpectedBusinessData: true }, null, []]) {
    assert.equal(parseSimulationRequest(bad), null);
  }
  const output = runSyntheticSimulation(message);
  assert.equal(output.summary.completedBatch, 4);
  assert.equal(output.summary.makespanSec, 15);
  assert.equal(output.summary.bottleneckTaktSec, 3);
  assert.equal(output.summary.theoreticalUph, 1200);
  assert.equal(output.summary.effectiveUph, 960);
});

test("replacement terminates prior Worker, ignores late events, releases completed Worker", () => {
  const a = worker(), b = worker();
  const ports = [a.port, b.port];
  const events: unknown[] = [];
  const controller = createSimulationController(() => ports.shift()!, (state) => events.push(state));
  controller.run(4);
  const stale = a.port.onmessage!;
  const staleError = a.port.onerror!;
  controller.run(5);
  assert.equal(a.stopCount(), 1);
  assert.deepEqual(a.sent, [{ kind: "run", requestId: 1, batchSize: 4 }]);
  assert.deepEqual(b.sent, [{ kind: "run", requestId: 2, batchSize: 5 }]);
  stale({ data: runSyntheticSimulation({ kind: "run", requestId: 1, batchSize: 4 }) });
  staleError();
  b.port.onmessage!({ data: runSyntheticSimulation({ kind: "run", requestId: 1, batchSize: 4 }) });
  assert.deepEqual(events, [{ status: "running" }, { status: "running" }]);
  b.port.onmessage!({ data: runSyntheticSimulation({ kind: "run", requestId: 2, batchSize: 5 }) });
  assert.equal((events.at(-1) as { status: string }).status, "done");
  assert.equal(b.stopCount(), 1);
  controller.dispose();
  assert.equal(b.stopCount(), 1);
});

test("matching malformed Worker response becomes retryable failure; dispose suppresses further work", () => {
  const a = worker(), b = worker();
  const ports = [a.port, b.port];
  const events: unknown[] = [];
  const controller = createSimulationController(() => ports.shift()!, (state) => events.push(state));
  controller.run(4);
  a.port.onmessage!({ data: { kind: "result", requestId: 1, summary: { completedBatch: 999 } } });
  assert.equal((events.at(-1) as { status: string }).status, "failed");
  assert.equal(a.stopCount(), 1);
  controller.run(4);
  assert.equal((events.at(-1) as { status: string }).status, "running");
  controller.dispose();
  controller.run(7);
  assert.equal(b.stopCount(), 1);
  assert.equal(b.sent.length, 1);
});
