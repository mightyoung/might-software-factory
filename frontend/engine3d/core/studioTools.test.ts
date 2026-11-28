import assert from "node:assert/strict";
import { test } from "node:test";
import { resizeEquipment } from "./equipmentResize.ts";
import { inspectPreviewUnits } from "./modelUnits.ts";
import type { ParametricEquipment } from "../schema/scene.ts";

const equipment: ParametricEquipment = {
  kind: "CONVEYOR", dimensions: { length: 4, width: 1, height: 0.9 }, rotationYRad: 0,
};
const close = (a: number, b: number) => assert.ok(Math.abs(a - b) < 1e-10);

test("horizontal resize holds the opposite edge, including rotated model coordinates", () => {
  const a = resizeEquipment(equipment, "+X", 0.51);
  assert.equal(a.dimensions.length, 4.5);
  assert.deepEqual(a.positionDelta, [0.25, 0, 0]);
  close(a.positionDelta[0] - a.dimensions.length / 2, -2);

  const b = resizeEquipment({ ...equipment, rotationYRad: Math.PI / 2 }, "-X", 0.5);
  close(b.positionDelta[0], 0);
  close(b.positionDelta[2], 0.25);
  const c = resizeEquipment(equipment, "-Z", -0.5);
  close(c.positionDelta[2] + c.dimensions.width / 2, 0.5);
});

test("height always stays on its original bottom plane and clamped ranges preserve the opposite face", () => {
  assert.deepEqual(resizeEquipment(equipment, "+Y", 0.5), {
    dimensions: { length: 4, width: 1, height: 1.4 }, positionDelta: [0, 0, 0],
  });
  const min = resizeEquipment(equipment, "+X", -100);
  assert.equal(min.dimensions.length, 0.2);
  close(min.positionDelta[0] - min.dimensions.length / 2, -2);
  assert.equal(resizeEquipment(equipment, "+X", 100).dimensions.length, 36);
  assert.throws(() => resizeEquipment(equipment, "+X", Infinity));
  assert.throws(() => resizeEquipment(equipment, "-Y" as never, 1));
});

test("large model dimensions never auto-convert and mm is an explicit opt in", () => {
  assert.deepEqual(inspectPreviewUnits([1000, 500, 300], "m"), { meters: [1000, 500, 300], checkUnits: true });
  assert.deepEqual(inspectPreviewUnits([1000, 500, 300], "mm"), { meters: [1, 0.5, 0.3], checkUnits: false });
  assert.deepEqual(inspectPreviewUnits([2, 0, 3], "m"), { meters: [2, 0, 3], checkUnits: false });
});
