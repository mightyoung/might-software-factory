import assert from "node:assert/strict";
import { test } from "node:test";
import { exporterGlb, multiMeshGlb, syntheticGlb } from "./glbFixture.ts";
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

test("reads interleaved exporter normals, flat material and baked ancestor transforms", () => {
  const model = openStaticGlb(exporterGlb());
  assert.deepEqual(model.metrics, { vertices: 4, triangles: 2, dimensions: [3, 2, 1] });
  assert.deepEqual(Array.from(model.geometry.getAttribute("normal").array.slice(0, 3)), [0, 1, 0]);
  model.dispose();
});

test("transforms imported normals with inverse transpose under nonuniform scale", () => {
  const model = openStaticGlb(exporterGlb((doc, bin) => {
    delete doc.nodes[0].rotation;
    doc.nodes[0].matrix = [2,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1];
    [0, 1, 2, 3].forEach((i) => {
      bin.setFloat32(i * 24 + 12, Math.SQRT1_2, true);
      bin.setFloat32(i * 24 + 16, Math.SQRT1_2, true);
    });
  }));
  const n = model.geometry.getAttribute("normal");
  assert.ok(Math.abs(n.getX(0) - 1 / Math.sqrt(5)) < 1e-6);
  assert.ok(Math.abs(n.getY(0) - 2 / Math.sqrt(5)) < 1e-6);
  model.dispose();
});

test("flattens small branching and multi-root scenes, rebasing mixed indices and generating missing normals", () => {
  for (const separateRoots of [false, true]) {
    const model = openStaticGlb(multiMeshGlb(separateRoots));
    assert.deepEqual(model.metrics, { vertices: 6, triangles: 2, dimensions: [5, 2, 3] });
    assert.deepEqual(Array.from(model.geometry.getIndex()!.array), [0, 1, 2, 3, 4, 5]);
    const normals = model.geometry.getAttribute("normal");
    assert.equal(normals.getX(0), 1);
    assert.equal(normals.getZ(0), 0);
    assert.equal(normals.getY(3), -1);
    model.dispose();
  }
});
