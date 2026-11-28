import assert from "node:assert/strict";
import { test } from "node:test";
import { createPrefabBank, prefabToDevice } from "./prefabBank.ts";
import { SceneDocumentSchema } from "../schema/scene.ts";

const draft = () => ({
  id: "conveyor-standard", name: "标准输送带",
  equipment: { kind: "CONVEYOR", dimensions: { length: 4, width: 1, height: 1 } },
  state: "warning", anchors: [{ id: "exit", position: [2, 0.5, 0] }],
});

test("writes immutable, detached versions under a stable prefab ID and lists newest first", () => {
  const bank = createPrefabBank();
  const input = draft();
  const v1 = bank.save(input);
  input.equipment.dimensions.length = 8;
  input.anchors[0].position[0] = 4;
  const v2 = bank.save(input);
  assert.equal(v1.version, 1);
  assert.equal(v2.version, 2);
  assert.equal(bank.get(v1.id, 1)?.equipment.dimensions.length, 4);
  assert.equal(bank.get(v2.id, 2)?.anchors[0].position[0], 4);
  for (const entry of [v1, v1.equipment, v1.equipment.dimensions, v1.anchors, v1.anchors[0], v1.anchors[0].position])
    assert.ok(Object.isFrozen(entry));
  assert.deepEqual(bank.list().map(({ id, version }) => [id, version]), [[v1.id, 2], [v1.id, 1]]);
});

test("validates anchors in local dimensions, duplicate IDs and only authored fields", () => {
  const bank = createPrefabBank();
  for (const bad of [
    { ...draft(), id: "BAD" }, { ...draft(), state: "arbitrary" }, { ...draft(), script: "alert(1)" },
    { ...draft(), anchors: [{ id: "exit", position: [2.1, 0, 0] }] },
    { ...draft(), anchors: [{ id: "exit", position: [0, -1, 0] }] },
    { ...draft(), anchors: [{ id: "exit", position: [0, 0, Infinity] }] },
    { ...draft(), anchors: [...draft().anchors, ...draft().anchors] },
  ]) assert.throws(() => bank.save(bad), /组件/);
  assert.equal(bank.list().length, 0);
});

test("projects a saved parametric template to the existing validated SceneDocument device", () => {
  const prefab = createPrefabBank().save(draft());
  const device = prefabToDevice(prefab, { id: "asset-one", position: [3, 0, 7] });
  const scene = SceneDocumentSchema.parse({ schemaVersion: 1, screenId: "mock", devices: [device] });
  assert.equal(scene.devices[0].id, "asset-one");
  assert.equal(scene.devices[0].state, "warning");
  assert.notEqual(device.equipment.dimensions, prefab.equipment.dimensions);
});

test("session bank bounds retained versions and does not overwrite older versions", () => {
  const bank = createPrefabBank();
  for (let i = 0; i < 8; i++) bank.save(draft());
  assert.throws(() => bank.save(draft()), /8/);
  assert.equal(bank.get("conveyor-standard", 1)?.version, 1);
  for (let i = 0; i < 11; i++) bank.save({ ...draft(), id: "prefab-" + i });
  assert.throws(() => bank.save({ ...draft(), id: "next-extra" }), /12/);
});
