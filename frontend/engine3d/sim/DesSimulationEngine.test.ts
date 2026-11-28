import { test } from "node:test";
import assert from "node:assert/strict";
import { runDiscreteEventSimulation } from "./DesSimulationEngine.ts";

const make = (id: string, cycleTimeSec: number, downstreamIds: string[] = [], bufferCapacity = 0) =>
  ({ id, name: id, cycleTimeSec, transferTimeSec: 0, bufferCapacity, downstreamIds });
const a = make("a", 1, ["b"]);
const b = make("b", 3);

test("zero-buffer line blocks the upstream station until downstream can take a unit", () => {
  const result = runDiscreteEventSimulation([b, a], 4);
  assert.equal(result.makespanSec, 13);
  assert.equal(result.completedBatch, 4);
  assert.equal(result.avgLeadTimeSec, 8.5);
  assert.equal(result.theoreticalUph, 1200);
  assert.equal(result.effectiveUph, 4 * 3600 / 13);
  assert.deepEqual(result.nodes.map((n) => [n.id, n.completedCount, n.busyTimeSec, n.blockedTimeSec, n.peakWaiting]),
    [["a", 4, 4, 6, 0], ["b", 4, 12, 0, 0]]);
});

test("one waiting slot reduces true upstream blocking, without losing units or changing the bottleneck", () => {
  const result = runDiscreteEventSimulation([a, { ...b, bufferCapacity: 1 }], 4);
  assert.equal(result.makespanSec, 13);
  assert.equal(result.nodes[0].blockedTimeSec, 3);
  assert.equal(result.nodes[1].peakWaiting, 1);
  assert.equal(result.bottleneckNodeId, "b");
  assert.deepEqual(result.nodes.map((n) => n.completedCount), [4, 4]);
});

test("serial transfer occupies its station; sub-second ties have deterministic metrics", () => {
  const input = [{ ...make("only", .1), transferTimeSec: .2 }];
  const result = runDiscreteEventSimulation(input, 3);
  assert.equal(result.makespanSec, .9);
  assert.equal(result.avgLeadTimeSec, .6);
  assert.equal(result.theoreticalUph, 12000);
  assert.equal(result.nodes[0].utilization, 1);
  assert.deepEqual(runDiscreteEventSimulation(input, 3), result);
});

test("full source batches conserve items over a three-stage line", () => {
  const result = runDiscreteEventSimulation([make("a", 3, ["b"]), make("b", 1, ["c"], 2), make("c", 2, [], 1)], 10);
  assert.equal(result.makespanSec, 33);
  assert.deepEqual(result.nodes.map((n) => n.completedCount), [10, 10, 10]);
  assert.deepEqual(result.nodes.map((n) => n.blockedTimeSec), [0, 0, 0]);
  assert.ok(result.nodes.every((n) => n.busyTimeSec + n.blockedTimeSec <= result.makespanSec));
});

test("rejects non-serial, cyclic, dangling, and duplicate topology, rather than guessing a route", () => {
  for (const input of [
    [a, { ...b, id: "a" }], [a], [a, { ...b, downstreamIds: ["a"] }],
    [make("a", 1, ["b", "c"]), b, make("c", 1)],
    [make("a", 1, ["c"]), make("b", 1, ["c"]), make("c", 1)], [b, make("c", 1)],
  ]) assert.throws(() => runDiscreteEventSimulation(input, 2), RangeError);
});

test("rejects out-of-budget or invalid inputs before execution", () => {
  for (const batch of [0, 1.5, 10001, Infinity]) {
    assert.throws(() => runDiscreteEventSimulation([b], batch), RangeError);
  }
  for (const station of [{ ...b, cycleTimeSec: 0 }, { ...b, cycleTimeSec: .0001 },
      { ...b, transferTimeSec: Infinity }, { ...b, bufferCapacity: -1 }, { ...b, id: " " }]) {
    assert.throws(() => runDiscreteEventSimulation([station], 2), RangeError);
  }
});
