import assert from "node:assert/strict";
import { test } from "node:test";
import { InstancedMesh, Matrix4, Vector3 } from "three";
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

test("read-only Player batches compatible local groups and preserves target anchors, fallback and cleanup", () => {
  const base = { label: "fixture", primitive: "box", size: [2, 4, 2], state: "normal" } as const;
  const item = (id: string, x: number, state: "normal" | "warning" = "normal") =>
    ({ ...base, id, state, position: [x, 0, 0] as [number, number, number], size: [...base.size] as [number, number, number] });
  const snapshot = { schemaVersion: 1 as const, screenId: "probe", devices: [
    item("a", 0), item("b", 1), item("far", 130), item("warn", 2, "warning"),
  ] };
  const player = buildSceneObjects(snapshot, "player-readonly");
  const studio = buildSceneObjects(snapshot);
  assert.equal(studio.group.children.filter((object) => object instanceof InstancedMesh).length, 0);
  const batch = player.group.children.find((object) => object instanceof InstancedMesh);
  assert.ok(batch instanceof InstancedMesh);
  assert.equal(batch.count, 2);
  assert.equal(batch.frustumCulled, true);
  assert.ok(batch.boundingSphere && batch.boundingSphere.radius < 10);
  const matrix = new Matrix4();
  batch.getMatrixAt(1, matrix);
  assert.deepEqual(new Vector3().setFromMatrixPosition(matrix).toArray(), player.devices.get("b")!.position.toArray());
  assert.deepEqual(player.devices.get("b")!.position.toArray(), [1, 2, 0]);
  const fallback = player.devices.get("far");
  assert.ok(fallback && "isMesh" in fallback);
  assert.equal(player.setState("a", "alarm"), false);
  assert.equal(player.setState("warn", "alarm"), false);
  let batchDisposals = 0;
  batch.addEventListener("dispose", () => batchDisposals++);
  player.dispose();
  player.dispose();
  assert.equal(batchDisposals, 1);
  assert.equal(player.devices.size, 0);
  studio.dispose();
});
