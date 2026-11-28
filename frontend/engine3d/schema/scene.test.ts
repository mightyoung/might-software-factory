import assert from "node:assert/strict";
import { test } from "node:test";
import { SceneDocumentSchema } from "./scene.ts";

const device = { id: "motor-1", label: "一号电机", primitive: "box", position: [2, 0, 3], size: [1, 2, 1], state: "warning" };
const scene = { schemaVersion: 1, screenId: "floor-a", devices: [device] };

test("accepts a bounded meter-based primitive and preserves stable ID", () => {
  assert.deepEqual(SceneDocumentSchema.parse(scene), scene);
});

test("rejects unsupported versions, duplicate IDs, arbitrary code and external assets", () => {
  for (const input of [
    { ...scene, schemaVersion: 2 },
    { ...scene, devices: [device, { ...device, label: "duplicate ID" }] },
    { ...scene, devices: [{ ...device, assetUrl: "https://example.com/model.glb" }] },
    { ...scene, devices: [{ ...device, script: "arbitrary code" }] },
    { ...scene, devices: [{ ...device, position: [0, Infinity, 0] }] },
    { ...scene, devices: [{ ...device, size: [1, 0, 1] }] },
    { ...scene, devices: [{ ...device, id: "../outside" }] },
  ]) assert.equal(SceneDocumentSchema.safeParse(input).success, false);
});
