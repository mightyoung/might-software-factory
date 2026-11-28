import assert from "node:assert/strict";
import { test } from "node:test";
import { buildSceneObjects } from "./sceneObjects.ts";

test("builds actual Three meshes, updates by stable ID and frees shared resources once", () => {
  const objects = buildSceneObjects({
    schemaVersion: 1, screenId: "floor-a", devices: [
      { id: "a", label: "A", primitive: "box", position: [1, 0, 2], size: [3, 2, 4], state: "normal" },
      { id: "b", label: "B", primitive: "box", position: [5, 0, 6], size: [1, 1, 1], state: "normal" },
    ],
  });
  const a = objects.devices.get("a")!;
  const b = objects.devices.get("b")!;
  assert.deepEqual(a.position.toArray(), [1, 1, 2]);
  assert.equal(a.geometry, b.geometry);
  assert.equal(a.material, b.material);
  assert.equal(objects.setState("missing", "alarm"), false);
  assert.equal(objects.setState("a", "alarm"), true);
  assert.notEqual(a.material, b.material);
  let geometry = 0, normal = 0, alarm = 0;
  a.geometry.addEventListener("dispose", () => geometry++);
  b.material.addEventListener("dispose", () => normal++);
  a.material.addEventListener("dispose", () => alarm++);
  objects.dispose();
  objects.dispose();
  assert.deepEqual([geometry, normal, alarm], [1, 1, 1]);
  assert.equal(objects.group.children.length, 0);
  assert.equal(objects.devices.size, 0);
  assert.equal(objects.setState("a", "alarm"), false);
});
