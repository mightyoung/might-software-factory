import assert from "node:assert/strict";
import { test } from "node:test";
import { projectedResizeDelta } from "./resizeProjection.ts";

test("screen motion maps to world metres along the face axis independent of perpendicular motion", () => {
  assert.equal(projectedResizeDelta([40, -20], [12, -6]), 0.3);
  assert.equal(projectedResizeDelta([0, -40], [120, 20]), -0.5);
  assert.equal(projectedResizeDelta([30, -40], [40, 30]), 0);
});

test("blocks near-edge-on and nonfinite projections and caps the gesture", () => {
  assert.equal(projectedResizeDelta([3, 0], [20, 20]), null);
  assert.equal(projectedResizeDelta([Infinity, 30], [5, 6]), null);
  assert.equal(projectedResizeDelta([15, 0], [10000, -3]), 100);
});
