import assert from "node:assert/strict";
import test from "node:test";
import { Mesh, Vector3 } from "three";
import { buildFloorplanGeometry } from "./floorplanGeometry.ts";

test("wall preview converts drafted endpoints to grounded metre geometry and releases shared resources", () => {
  const { group, dispose } = buildFloorplanGeometry({ walls: [
    { start: [-10, 20], end: [20, 60] },
    { start: [20, 60], end: [30, 60] },
  ] });
  const [wall, second] = group.children;
  assert.ok(wall instanceof Mesh && second instanceof Mesh);
  const near = (actual: Vector3, expected: Vector3) => assert.ok(actual.distanceTo(expected) < 1e-10);
  near(wall.localToWorld(new Vector3(-0.5, -0.5, 0)), new Vector3(-1, 0, 2));
  near(wall.localToWorld(new Vector3(0.5, 0.5, 0)), new Vector3(2, 3, 6));
  assert.equal(wall.scale.z, 0.2);
  assert.equal(wall.geometry, second.geometry);
  assert.equal(wall.material, second.material);
  let releases = 0;
  wall.geometry.addEventListener("dispose", () => { releases++; });
  wall.material.addEventListener("dispose", () => { releases++; });
  dispose();
  assert.equal(group.children.length, 0);
  assert.equal(releases, 2);
});
