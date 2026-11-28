import assert from "node:assert/strict";
import { test } from "node:test";
import { Box3 } from "three";
import { SceneDocumentSchema } from "../schema/scene.ts";
import { buildSceneObjects } from "./sceneObjects.ts";

const equipment = (id: string, kind: string, length: number, width: number, height: number) => ({
  id, label: id, position: [0, 2, 0], state: "normal",
  equipment: { kind, dimensions: { length, width, height }, rotationYRad: 0 },
});
const scene = (devices: unknown[]) => ({ schemaVersion: 1, screenId: "workshop", devices });

test("preserves v1 compatibility while rejecting nonfinite geometry, extra keys and too many procedural devices", () => {
  for (const invalid of [
    equipment("test", "CONVEYOR", 0.1, 1, 1),
    equipment("test", "CONVEYOR", 1, 1, Infinity),
    equipment("test", "UNIMPLEMENTED", 1, 1, 1),
    { ...equipment("test", "CONVEYOR", 1, 1, 1), primitive: "box" },
    { ...equipment("test", "CONVEYOR", 1, 1, 1), assetUrl: "https://example.com/m.glb" },
  ]) assert.equal(SceneDocumentSchema.safeParse(scene([invalid])).success, false);
  assert.equal(SceneDocumentSchema.safeParse(scene(Array.from({ length: 25 }, (_, i) =>
    equipment("item-" + i, "CONVEYOR", 1, 1, 1)))).success, false);
});

test("rebuilds a longer conveyor with additional supports and anchors, on its local floor", () => {
  const a = buildSceneObjects(SceneDocumentSchema.parse(scene([equipment("short", "CONVEYOR", 3, 1, 1)])));
  const b = buildSceneObjects(SceneDocumentSchema.parse(scene([equipment("long", "CONVEYOR", 9, 1, 1)])));
  const short = a.devices.get("short")!;
  const long = b.devices.get("long")!;
  assert.ok(long.children.length > short.children.length);
  assert.equal(b.ports.get("long")?.outPort.x, 4.5);
  assert.equal(b.ports.get("long")?.inPort.x, -4.5);
  const bounds = new Box3().setFromObject(long);
  assert.ok(Math.abs(bounds.min.y - 2) < 1e-6);
  assert.ok(Math.abs(bounds.max.y - 3) < 1e-6);
  a.dispose();
  b.dispose();
});

test("generates corridor, robot, and spiral as distinct structures and owns resources only once", () => {
  const objects = buildSceneObjects(SceneDocumentSchema.parse(scene([
    equipment("corridor", "CORRIDOR", 9, 2, 1.2),
    equipment("robot", "ROBOT_ARM", 1.5, 1.5, 2.2),
    equipment("spiral", "SPIRAL_CONVEYOR", 3, 3, 4.2),
  ])));
  const corridor = objects.devices.get("corridor")!;
  const robot = objects.devices.get("robot")!;
  const spiral = objects.devices.get("spiral")!;
  assert.ok(spiral.children.length > corridor.children.length);
  assert.ok(corridor.children.length > robot.children.length);
  const frameGeometry = corridor.children[0].geometry;
  let releases = 0;
  frameGeometry.addEventListener("dispose", () => releases++);
  const prior = robot.children.at(-1)!.material;
  assert.equal(objects.setState("robot", "alarm"), true);
  assert.notEqual(robot.children.at(-1)!.material, prior);
  objects.dispose();
  objects.dispose();
  assert.equal(releases, 1);
  assert.equal(objects.ports.size, 0);
});
