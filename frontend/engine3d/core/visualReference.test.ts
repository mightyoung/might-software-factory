import assert from "node:assert/strict";
import { test } from "node:test";
import { Box3 } from "three";
import { createVisualReferenceSlot } from "./visualReference.ts";
import { openStaticGlb } from "./glbIntake.ts";
import { syntheticGlb } from "./glbFixture.ts";

test("grounds and centres a verified reference in declared metres, and releases replaced or removed geometry once", () => {
  const slot = createVisualReferenceSlot();
  const first = openStaticGlb(syntheticGlb());
  let released = 0;
  first.geometry.addEventListener("dispose", () => released++);
  const object = slot.replace(first, "m", [3, -4]);
  const bounds = new Box3().setFromObject(object);
  assert.deepEqual(bounds.min.toArray(), [2, 0, -5]);
  assert.deepEqual(bounds.max.toArray(), [4, 2, -3]);
  assert.equal(slot.group.children.length, 1);
  const second = openStaticGlb(syntheticGlb());
  second.geometry.addEventListener("dispose", () => released++);
  const small = slot.replace(second, "mm", [0, 0]);
  assert.deepEqual(small.scale.toArray(), [0.001, 0.001, 0.001]);
  assert.equal(released, 1);
  slot.clear();
  slot.dispose();
  slot.dispose();
  assert.equal(released, 2);
  assert.equal(slot.group.children.length, 0);
});

test("rejects oversized, out-of-range, or duplicate owned placement with existing reference unchanged", () => {
  const slot = createVisualReferenceSlot();
  const first = openStaticGlb(syntheticGlb());
  const next = openStaticGlb(syntheticGlb());
  slot.replace(first, "m", [0, 0]);
  assert.throws(() => slot.replace(first, "m", [1, 0]), /引用/);
  assert.throws(() => slot.replace(next, "m", [11, 0]), /坐标/);
  next.geometry.scale(100, 100, 100);
  next.geometry.computeBoundingBox();
  assert.throws(() => slot.replace(next, "m", [0, 0]), /20/);
  assert.equal(slot.group.children.length, 1);
  next.dispose(); // rejected candidate ownership remains with caller
  slot.dispose();
});
