import assert from "node:assert/strict";
import { test } from "node:test";
import { FloorplanDraft, polygonArea } from "./floorplan.ts";

test("snaps 0.1/0.5/1 m, locks the next leg and allows connected walls", () => {
  const tool = new FloorplanDraft();
  tool.setMode("wall");
  tool.setGrid("0.5");
  tool.add([0.2, 0.24]);
  assert.deepEqual(tool.preview([4.21, 0.69], true), [40, 0]);
  tool.add([4.21, 0.69], true);
  tool.setGrid("0.1");
  tool.add([4.01, 3.04], true);
  assert.deepEqual(tool.walls.map(({ start, end }) => [start, end]), [
    [[0,0], [40,0]], [[40,0], [40,30]],
  ]);
  tool.setGrid("1");
  assert.deepEqual(tool.preview([-1.51, 2.4]), [-20, 20]);
  assert.throws(() => tool.add([Infinity, 0]));
});

test("computes exact square metres on the snapped scale and only commits valid zones", () => {
  const tool = new FloorplanDraft();
  tool.setMode("zone");
  for (const point of [[0,0], [4,0], [4,3], [0,3]] as const) tool.add(point);
  assert.equal(tool.finishZone().area, 12);
  assert.equal(tool.zones.length, 1);
  assert.equal(tool.points.length, 0);
  assert.equal(polygonArea([[0,0], [11,0], [11,11], [0,11]]), 1.21);
});

test("rejects closed self-intersections, duplicates, and degenerate inputs without losing draft", () => {
  for (const points of [
    [[0,0],[4,4],[0,4],[3,0]],
    [[0,0],[2,0],[4,0]],
    [[0,0],[4,0],[2,0],[2,3]],
    [[0,0],[4,0],[4,4],[0,0],[0,4]],
  ] as const) {
    const tool = new FloorplanDraft();
    tool.setMode("zone");
    for (const point of points) tool.add(point);
    assert.throws(() => tool.finishZone());
    assert.equal(tool.zones.length, 0);
    assert.equal(tool.points.length, points.length);
    tool.cancel();
    assert.equal(tool.points.length, 0);
  }
});

test("undo and redo stable committed walls and zones without replacing the open polygon", () => {
  const draft = new FloorplanDraft();
  draft.add([0, 0]); draft.add([4, 0]);
  const wall = draft.walls[0];
  draft.setMode("zone");
  draft.add([0, 0]); draft.add([4, 0]); draft.add([0, 3]);
  assert.equal(draft.canUndo, false);
  assert.equal(draft.undo(), false);
  assert.equal(draft.points.length, 3);
  const zone = draft.finishZone();
  assert.equal(draft.undo(), true);
  assert.deepEqual(draft.walls, [wall]);
  assert.deepEqual(draft.zones, []);
  assert.equal(draft.undo(), true);
  assert.deepEqual(draft.walls, []);
  assert.equal(draft.redo(), true);
  assert.equal(draft.redo(), true);
  assert.deepEqual(draft.walls, [wall]);
  assert.deepEqual(draft.zones, [zone]);
  draft.undo();
  draft.setMode("wall");
  draft.add([0, 0]); draft.add([0, 4]);
  assert.equal(draft.canRedo, false);
  assert.ok(draft.walls[1].id > zone.id);
  draft.restore({ walls: draft.walls, zones: draft.zones });
  assert.equal(draft.canUndo, false);
  assert.equal(draft.canRedo, false);
});

test("only recent 20 committed shapes enter history and failed edits never erase redo", () => {
  const draft = new FloorplanDraft();
  draft.setGrid("0.1");
  for (let i = 0; i < 22; i++) draft.add([i / 10, 0]);
  for (let i = 0; i < 20; i++) assert.equal(draft.undo(), true);
  assert.equal(draft.canUndo, false);
  assert.equal(draft.walls.length, 1);
  draft.add([0, 0]);
  assert.throws(() => draft.add([0.1, 0]), /已存在/);
  assert.equal(draft.canRedo, true);
});

test("does not create a history entry for an ID outside the exported draft schema", () => {
  const draft = new FloorplanDraft();
  draft.restore({ walls: [{ id: 10_000, start: [0, 0], end: [0, 20] }], zones: [] });
  draft.add([0, 0]);
  assert.throws(() => draft.add([1, 0]), /编号/);
  assert.equal(draft.walls.length, 1);
  assert.equal(draft.canUndo, false);
});
