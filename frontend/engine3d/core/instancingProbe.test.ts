import assert from "node:assert/strict";
import { test } from "node:test";
import { InstancedMesh, Matrix4, Vector3 } from "three";
import { createInstancingFixture } from "./instancingProbe.ts";

test("dedicated fixtures keep identical placements, batch the mesh, and release owned assets once", () => {
  const reference = createInstancingFixture("individual", 500);
  const batch = createInstancingFixture("instanced", 500);
  const object = batch.group.children[0] as InstancedMesh;
  assert.ok(object.isInstancedMesh && object.count === 500);
  assert.equal(batch.group.children.length, 1);
  assert.equal(reference.group.children.length, 500);
  const matrix = new Matrix4();
  for (const i of [0, 41, 499]) {
    object.getMatrixAt(i, matrix);
    assert.deepEqual(new Vector3().setFromMatrixPosition(matrix).toArray(), reference.group.children[i].position.toArray());
  }
  reference.dispose();
  batch.dispose();
  batch.dispose();
  assert.equal(batch.group.children.length, 0);
  assert.deepEqual(batch.releaseCounts(), { geometry: 1, material: 1, instance: 1 });
  assert.deepEqual(reference.releaseCounts(), { geometry: 1, material: 1, instance: 0 });
});

test("fixture bounds its count and mode before allocating", () => {
  for (const count of [-1, 0, 1.1, 501, Infinity]) assert.throws(() => createInstancingFixture("instanced", count));
  assert.throws(() => createInstancingFixture("unknown", 30));
});
