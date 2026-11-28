import assert from "node:assert/strict";
import { test } from "node:test";
import { createPrefabBank } from "./prefabBank.ts";
import { placePrefab } from "./prefabPlacement.ts";
import type { SceneDocument } from "../schema/scene.ts";

const scene: SceneDocument = { schemaVersion: 1, screenId: "synthetic", devices: [] };
const definition = (length: number) => ({
  id: "standard", name: "测试组件",
  equipment: { kind: "CONVEYOR", dimensions: { length, width: 1, height: 1 } },
  state: "warning", anchors: [{ id: "centre", position: [0, 0, 0] }],
});

test("pins selected immutable ID/version in actual scene while subsequent versions leave it unchanged", () => {
  const bank = createPrefabBank();
  const v1 = bank.save(definition(4));
  const first = placePrefab(scene, v1, "prefab-1", [2, -3]);
  const v2 = bank.save(definition(8));
  const second = placePrefab(first.scene, v2, "prefab-2", [4, 5]);
  assert.equal(scene.devices.length, 0);
  assert.deepEqual(first.placement, { prefabId: "standard", version: 1, deviceId: "prefab-1", metres: [2, -3] });
  assert.ok(Object.isFrozen(first.placement) && Object.isFrozen(first.placement.metres));
  assert.equal(second.placement.version, 2);
  assert.deepEqual(second.scene.devices.map((d) => d.position), [[2, 0, -3], [4, 0, 5]]);
  assert.deepEqual(second.scene.devices.map((d) => "equipment" in d && d.equipment.dimensions.length), [4, 8]);
});

test("rejects duplicate IDs, invalid coordinates or over-capacity placement without mutating original", () => {
  const v1 = createPrefabBank().save(definition(4));
  const first = placePrefab(scene, v1, "prefab-1", [0, 0]);
  assert.throws(() => placePrefab(first.scene, v1, "prefab-1", [0, 0]), /标识/);
  for (const x of [Infinity, NaN, 11]) assert.throws(() => placePrefab(first.scene, v1, "prefab-2", [x, 0]), /坐标/);
  const full = { ...scene, devices: Array.from({ length: 24 }, (_, i) => ({ ...first.scene.devices[0], id: "other-" + i })) };
  assert.throws(() => placePrefab(full, v1, "prefab-extra", [0, 0]), /24/);
  assert.equal(first.scene.devices.length, 1);
});
