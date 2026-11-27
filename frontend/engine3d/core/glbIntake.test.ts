import assert from "node:assert/strict";
import { test } from "node:test";
import { syntheticGlb } from "./glbFixture.ts";
import { openStaticGlb, type StaticGlb } from "./glbIntake.ts";
import { PreviewSlot } from "./PreviewSlot.ts";

test("reads real indexed GLB triangles and actual bounds, not claimed metadata", () => {
  const model = openStaticGlb(syntheticGlb((doc) => { doc.accessors[0].max = [9, 9, 9]; }));
  assert.deepEqual(model.metrics, { vertices: 4, triangles: 4, dimensions: [2, 2, 2] });
  assert.equal(model.geometry.getIndex()!.count, 12);
  model.dispose();
});

test("rejects malformed, out-of-scope or out-of-budget GLB before display", () => {
  for (const input of [
    new ArrayBuffer(8 * 1024 * 1024 + 1),
    syntheticGlb().slice(0, -4),
    syntheticGlb((doc) => { doc.buffers[0].uri = "https://example.com/mesh.bin"; }),
    syntheticGlb((doc) => { doc.extensionsUsed = ["KHR_draco_mesh_compression"]; }),
    syntheticGlb((doc) => { doc.nodes[0].children = [0]; }),
    syntheticGlb((doc) => { doc.accessors[0].count = 100_001; }),
    syntheticGlb((doc) => { doc.bufferViews[0].byteLength = 900; }),
    syntheticGlb((_doc, bin) => { bin.setFloat32(0, Infinity, true); }),
    syntheticGlb((_doc, bin) => { bin.setUint16(48, 4, true); }),
  ]) assert.throws(() => openStaticGlb(input), /GLB/);
});

test("50 replacements and repeated clear dispose each owned geometry exactly once", () => {
  const slot = new PreviewSlot();
  let disposals = 0;
  const load = (): StaticGlb => {
    const model = openStaticGlb(syntheticGlb());
    model.geometry.addEventListener("dispose", () => disposals++);
    return model;
  };
  for (let i = 0; i < 50; i++) slot.replace(load());
  assert.equal(disposals, 49);
  slot.clear();
  slot.clear();
  assert.equal(disposals, 50);
  assert.equal(slot.current, null);
});
