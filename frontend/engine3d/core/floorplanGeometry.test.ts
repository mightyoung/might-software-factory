import { strict as assert } from "node:assert";
import { test } from "node:test";
import { Box3, Vector3 } from "three";
import { buildFloorplanGeometry } from "./floorplanGeometry.ts";

test("makes a detached immutable wall snapshot and builds grounded, rotated metre geometry", () => {
  const start: [number, number] = [10, -20];
  const end: [number, number] = [40, 20];
  const authored = { id: 7, start, end };
  const model = buildFloorplanGeometry({ walls: [authored] });
  start[0] = 999;
  authored.id = 99;
  assert.deepEqual(model.snapshot.walls, [{ id: 7, start: [10, -20], end: [40, 20] }]);
  for (const value of [model.snapshot, model.snapshot.walls, model.snapshot.walls[0], model.snapshot.walls[0].start])
    assert.ok(Object.isFrozen(value));
  const wall = model.walls.get(7)!;
  assert.equal(wall.name, "floorplan-wall:7");
  assert.deepEqual(wall.position.toArray(), [2.5, 1.5, 0]);
  assert.deepEqual(wall.scale.toArray(), [5, 3, 0.2]);
  model.group.updateMatrixWorld();
  const near = (a: Vector3, b: Vector3) => assert.ok(a.distanceTo(b) < 1e-9, a.toArray().join(","));
  near(wall.localToWorld(new Vector3(-0.5, 0, 0)), new Vector3(1, 1.5, -2));
  near(wall.localToWorld(new Vector3(0.5, 0, 0)), new Vector3(4, 1.5, 2));
  const bounds = new Box3().setFromObject(model.group);
  assert.equal(bounds.min.y, 0);
  assert.equal(bounds.max.y, 3);
  model.dispose();
});

test("separate walls share resources that their floorplan owns; release is idempotent", () => {
  const model = buildFloorplanGeometry({ walls: [
    { id: 1, start: [0, 0], end: [10, 0] }, { id: 2, start: [10, 0], end: [10, 10] },
  ] });
  const independent = buildFloorplanGeometry({ walls: [{ id: 1, start: [0, 0], end: [10, 0] }] });
  const a = model.walls.get(1)!, b = model.walls.get(2)!;
  assert.equal(a.geometry, b.geometry);
  assert.equal(a.material, b.material);
  assert.notEqual(a.geometry, independent.walls.get(1)!.geometry);
  let geometries = 0, materials = 0;
  a.geometry.addEventListener("dispose", () => geometries++);
  a.material.addEventListener("dispose", () => materials++);
  model.dispose();
  model.dispose();
  assert.deepEqual([geometries, materials], [1, 1]);
  assert.equal(model.group.children.length, 0);
  assert.equal(model.walls.size, 0);
  assert.equal(independent.walls.size, 1);
  independent.dispose();
});

test("rejects invalid IDs, coordinates, zero-length/reversed duplicate walls and excess input", () => {
  const valid = { id: 1, start: [0, 0], end: [10, 0] } as const;
  for (const walls of [
    [{ ...valid, id: 0 }], [{ ...valid, start: [101, 0] }],
    [{ ...valid, end: [0, 0] }], [{ ...valid, end: [0, 0.1] }],
    [valid, { ...valid, end: [10, 10] }],
    [valid, { id: 2, start: valid.end, end: valid.start }],
    Array.from({ length: 101 }, () => valid),
  ] as const) assert.throws(() => buildFloorplanGeometry({ walls }));
  const empty = buildFloorplanGeometry({ walls: [] });
  assert.equal(empty.group.children.length, 0);
  empty.dispose();
});
