import assert from "node:assert/strict";
import { test } from "node:test";
import { createStudioPublication } from "./studioPublication.ts";

const draft = (length = 20) => JSON.stringify({
  snapshotVersion: 1, scene: { schemaVersion: 1, screenId: "factory-demo", devices: [] },
  floorplan: { walls: [{ id: 1, start: [0, 0], end: [length, 0] }], zones: [] },
});
const keyA = { tenantId: "north", screenId: "factory-demo" }, keyB = { ...keyA, tenantId: "south" };
const publisher = { tenantId: "north", permissions: ["studio:publish"] as const };
const reader = { tenantId: "north", permissions: ["player:read"] as const };

test("publishes immutable read-only versions, unchanged content is idempotent and stale writes never advance", () => {
  const store = createStudioPublication();
  assert.equal(store.read(reader, keyA), undefined);
  const first = store.publish(publisher, keyA, 0, draft());
  const equivalent = JSON.stringify(JSON.parse(draft()), null, 2);
  assert.equal(store.publish(publisher, keyA, 1, equivalent), first);
  const second = store.publish(publisher, keyA, 1, draft(30));
  assert.equal(second.version, 2);
  assert.throws(() => store.publish(publisher, keyA, 1, draft(10)), /版本冲突/);
  assert.throws(() => store.publish(publisher, keyA, 1, draft(30)), /版本冲突/);
  assert.equal(store.read(reader, keyA), second);
  assert.equal(store.read(reader, keyA, 1), first);
  assert.equal(store.read(reader, keyA, 3), undefined);
  assert.deepEqual(first.snapshot.floorplan.walls[0].end, [20, 0]);
  for (const value of [first, first.snapshot, first.snapshot.floorplan.walls, first.snapshot.floorplan.walls[0].end])
    assert.ok(Object.isFrozen(value));
});

test("requires explicit matching tenant and distinct permissions, never sees another tenant history", () => {
  const store = createStudioPublication();
  const deny = /权限/;
  assert.throws(() => store.publish(reader, keyA, 0, draft()), deny);
  assert.throws(() => store.read(publisher, keyA), deny);
  assert.throws(() => store.publish(publisher, keyB, 0, draft()), deny);
  assert.throws(() => store.read(reader, keyB), deny);
  assert.throws(() => store.read({ ...reader, tenantId: "" }, { ...keyA, tenantId: "" }), deny);
  store.publish(publisher, keyA, 0, draft());
  assert.equal(store.read({ ...reader, tenantId: "south" }, keyB), undefined);
  const south = store.publish({ ...publisher, tenantId: "south" }, keyB, 0, draft(40));
  assert.equal(south.version, 1);
  assert.deepEqual(south.snapshot.floorplan.walls[0].end, [40, 0]);
  assert.deepEqual(store.read(reader, keyA)?.snapshot.floorplan.walls[0].end, [20, 0]);
});

test("validates existing Studio v1 contract, target screen and versions before mutating", () => {
  const store = createStudioPublication();
  assert.throws(() => store.publish(publisher, keyA, 0, "{invalid}"), /JSON/);
  assert.throws(() => store.publish(publisher, keyA, 0, draft().replace("factory-demo", "other")), /标识/);
  assert.throws(() => store.publish(publisher, keyA, -1, draft()), /版本/);
  assert.throws(() => store.publish(publisher, keyA, 0, draft().replace('"snapshotVersion":1', '"snapshotVersion":2')), /Studio/);
  assert.throws(() => store.read(reader, keyA, 0), /版本/);
  assert.equal(store.read(reader, keyA), undefined);
  assert.equal(store.publish(publisher, keyA, 0, draft()).version, 1);
});

test("limits retained versions and emits reusable Studio source without derived fields", () => {
  const store = createStudioPublication();
  const source = JSON.parse(draft());
  source.floorplan.zones.push({ id: 2, points: [[0, 0], [20, 0], [20, 30], [0, 30]] });
  const first = store.publish(publisher, keyA, 0, JSON.stringify(source));
  assert.equal(first.snapshot.floorplan.zones[0].area, 6);
  assert.equal(Object.hasOwn(JSON.parse(first.source).floorplan.zones[0], "area"), false);
  assert.equal(store.publish(publisher, keyA, 1, first.source), first);
  for (let i = 1; i < 8; i++) store.publish(publisher, keyA, i, draft(i * 10));
  assert.throws(() => store.publish(publisher, keyA, 8, draft(90)), /容量/);
  assert.equal(store.read(reader, keyA, 1), first);
  assert.equal(store.read(reader, keyA)?.version, 8);
});
