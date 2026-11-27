import assert from "node:assert/strict";
import { test } from "node:test";
import { parseStudioSnapshot } from "./studioSnapshot.ts";

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

test("keeps an authored named camera sequence and refuses unknown or duplicated devices", () => {
  const devices = [{ id: "sensor-one", label: "传感器", primitive: "box", position: [0,0,0], size: [1,1,1], state: "normal" }];
  const valid = { ...snapshot, scene: { ...snapshot.scene, devices },
    cameraTour: [{ deviceId: "sensor-one", label: "入口机位", dwellSeconds: 8 }] };
  assert.deepEqual(parseStudioSnapshot(JSON.stringify(valid)).cameraTour, valid.cameraTour);
  assert.throws(() => parseStudioSnapshot(JSON.stringify({ ...valid, cameraTour: [...valid.cameraTour, ...valid.cameraTour] })));
  assert.throws(() => parseStudioSnapshot(JSON.stringify({ ...valid, cameraTour: [{ ...valid.cameraTour[0], deviceId: "missing" }] })));
  assert.throws(() => parseStudioSnapshot(JSON.stringify({ ...valid, cameraTour: [{ ...valid.cameraTour[0], dwellSeconds: 31 }] })));
});

test("accepts a bounded allowlisted HUD selection and never accepts arbitrary HTML or endpoints", () => {
  const hud = { ...snapshot, hudWidgets: ["devices", "alarms", "zones"] };
  assert.deepEqual(parseStudioSnapshot(JSON.stringify(hud)).hudWidgets, hud.hudWidgets);
  assert.throws(() => parseStudioSnapshot(JSON.stringify({ ...hud, hudWidgets: ["devices", "devices"] })));
  assert.throws(() => parseStudioSnapshot(JSON.stringify({ ...hud, hudWidgets: [{ url: "https://example.com" }] })));
});
