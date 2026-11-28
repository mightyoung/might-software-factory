import assert from "node:assert/strict";
import { test } from "node:test";
import { FloorplanDraft } from "../core/floorplan.ts";
import { MAX_STUDIO_BYTES, parseStudioSnapshot, serializeStudioSnapshot } from "./studioSnapshot.ts";

const snapshot = {
  snapshotVersion: 1,
  scene: { schemaVersion: 1, screenId: "test-line", devices: [] },
  floorplan: {
    walls: [{ id: 1, start: [0, 0], end: [20, 0] }],
    zones: [{ id: 2, points: [[0, 0], [20, 0], [20, 30], [0, 30]] }],
  },
};
test("round trips typed scene and geometry, computing rather than accepting area", () => {
  const parsed = parseStudioSnapshot(JSON.stringify(snapshot));
  assert.equal(parsed.scene.screenId, "test-line");
  assert.equal(parsed.floorplan.zones[0].area, 6);
  assert.equal(parsed.floorplan.walls[0].id, 1);
});
test("rejects duplicate or crossing geometry, outside assets, unsupported versions, and unbounded text", () => {
  for (const draft of [
    { ...snapshot, snapshotVersion: 2 },
    { ...snapshot, network: { url: "https://example.com" } },
    { ...snapshot, floorplan: { ...snapshot.floorplan, walls: [snapshot.floorplan.walls[0], { id: 4, start: [20,0], end: [0,0] }] } },
    { ...snapshot, floorplan: { ...snapshot.floorplan, zones: [{ id: 1, points: [[0,0],[0,30],[20,30],[20,0]] }] }},
    { ...snapshot, floorplan: { ...snapshot.floorplan, zones: [{ id: 2, points: [[0,0],[30,30],[0,30],[40,0]] }] }},
  ]) assert.throws(() => parseStudioSnapshot(JSON.stringify(draft)));
  assert.throws(() => parseStudioSnapshot(" ".repeat(256 * 1024 + 1)), /256/);
});

test("continue editing after importing a maximum shape ID without colliding", () => {
  const parsed = parseStudioSnapshot(JSON.stringify({ ...snapshot, floorplan: {
    ...snapshot.floorplan, walls: [{ id: 10000, start: [0, 0], end: [20, 0] },
      { id: 1, start: [20, 0], end: [30, 0] }],
  } }));
  const draft = new FloorplanDraft();
  draft.restore(parsed.floorplan);
  draft.add([0, 1]);
  draft.add([2, 1]);
  assert.deepEqual(parseStudioSnapshot(serializeStudioSnapshot(parsed.scene, draft.snapshot()))
    .floorplan.walls.map(({ id }) => id), [10000, 1, 3]);
});

test("export preserves an upper-range compact draft in UTF-8 within the input cap", () => {
  const value = { ...snapshot, scene: { ...snapshot.scene, devices: Array.from({ length: 460 }, (_, i) => ({
    id: `device-${i}`, label: "合".repeat(80), primitive: "box", position: [1, 2, 3],
    size: [1, 2, 3], state: "normal",
  })) } };
  for (const device of value.scene.devices) device.label = "\\".repeat(10) + "\u0001".repeat(70);
  const raw = JSON.stringify(value);
  assert.ok(Buffer.byteLength(raw) > 240 * 1024 && Buffer.byteLength(raw) <= MAX_STUDIO_BYTES);
  assert.ok(Buffer.byteLength(JSON.stringify(value, null, 2)) > MAX_STUDIO_BYTES);
  const parsed = parseStudioSnapshot(raw);
  const draft = new FloorplanDraft();
  draft.restore(parsed.floorplan);
  const exported = serializeStudioSnapshot(parsed.scene, draft.snapshot());
  assert.equal(Buffer.byteLength(exported), Buffer.byteLength(raw));
  assert.deepEqual(parseStudioSnapshot(exported), parsed);
});

test("the import cap also accounts for defaulted rotation in canonical export", () => {
  const devices = Array.from({ length: 480 }, (_, i) => ({
    id: `device-${i}`, label: "a".repeat(80), primitive: "box", position: [1, 2, 3],
    size: [1, 2, 3], state: "normal",
  }));
  const value = { ...snapshot, scene: { ...snapshot.scene, devices: [
    ...devices, { id: "equipment", label: "equipment", position: [0, 0, 0], state: "normal",
      equipment: { kind: "CONVEYOR", dimensions: { length: 1, width: 1, height: 1 } } },
  ] } };
  const missing = MAX_STUDIO_BYTES - Buffer.byteLength(JSON.stringify(value));
  assert.ok(missing > 0 && missing < devices.length * 400);
  for (let n = 0; n < Math.floor(missing / 5); n++) {
    const device = devices[Math.floor(n / 80)];
    device.label = device.label.replace("a", "\u0001");
  }
  const raw = JSON.stringify(value);
  assert.ok(MAX_STUDIO_BYTES - Buffer.byteLength(raw) < 5);
  assert.throws(() => parseStudioSnapshot(raw), /256/);
});
