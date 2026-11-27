import assert from "node:assert/strict";
import { test } from "node:test";
import { createResizeHistory } from "./resizeHistory.ts";
import { SceneDocumentSchema } from "../schema/scene.ts";

const sample = (length: number, x: number, state = "normal") => SceneDocumentSchema.parse({
  schemaVersion: 1, screenId: "synthetic",
  devices: [
    { id: "scale", label: "Fixture", state, position: [x, 0, 1],
      equipment: { kind: "CONVEYOR", dimensions: { length, width: 1, height: 1 } } },
  ],
});

test("undo and redo restore anchored position/dimensions without changing unrelated fields", () => {
  const history = createResizeHistory();
  const before = sample(4, -3), after = sample(4.5, -2.75);
  history.record(before, after, "scale");
  const undo = history.step("undo", sample(4.5, -2.75, "warning"), () => true);
  assert.ok(undo);
  assert.deepEqual(undo.scene.devices, sample(4, -3, "warning").devices);
  assert.equal(history.canRedo(), true);
  const redo = history.step("redo", undo.scene, () => true);
  assert.ok(redo);
  assert.deepEqual(redo.scene.devices, sample(4.5, -2.75, "warning").devices);
  assert.equal(history.canRedo(), false);
});

test("bounded history ignores no-op and clears stale edits; new edit clears redo", () => {
  const history = createResizeHistory();
  history.record(sample(1, 0), sample(1, 0), "scale");
  assert.equal(history.canUndo(), false);
  for (let i = 0; i < 21; i++) history.record(sample(1, i), sample(1, i + 1), "scale");
  let scene = sample(1, 21);
  for (let i = 0; i < 20; i++) { const step = history.step("undo", scene, () => true); assert.ok(step); scene = step.scene; }
  assert.equal(history.canUndo(), false);
  assert.deepEqual(scene.devices[0].position, [1,0,1]);
  history.record(scene, sample(2, 1), "scale");
  assert.equal(history.canRedo(), false);
  assert.equal(history.step("undo", sample(2, -1), () => true), null);
  assert.equal(history.canUndo(), false);
  history.record(sample(2, 1), sample(3, 1), "scale");
  assert.equal(history.step("undo", sample(3, 1), () => false), null);
  assert.equal(history.canUndo(), true);
  history.clear();
  assert.equal(history.canUndo(), false);
});
