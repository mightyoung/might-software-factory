import assert from "node:assert/strict";
import { test } from "node:test";
import { MeshoptEncoder } from "meshoptimizer/encoder";
import { openStaticGlb } from "./glbIntake.ts";
import { openMeshoptPreview } from "./meshoptPreview.ts";
import { encodeGlb } from "./glbFixture.ts";

const coords = [0,0,0, 1,0,0, 0,2,0, 0,0,0, 1,0,0, 0,0,3];
async function fixture(change?: (doc: any, packed: Uint8Array) => void, values = coords) {
  // Original synthetic triangles encoded by MIT meshoptimizer 1.1.1 (existing lockfile dev transitive).
  const raw = new ArrayBuffer(values.length * 4);
  values.forEach((value, i) => new DataView(raw).setFloat32(i * 4, value, true));
  await MeshoptEncoder.ready;
  const packed = MeshoptEncoder.encodeGltfBuffer(new Uint8Array(raw), 6, 12, "ATTRIBUTES", 0);
  const doc = {
    asset: { version: "2.0" }, extensionsRequired: ["EXT_meshopt_compression"], extensionsUsed: ["EXT_meshopt_compression"],
    scene: 0, scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 } }] }],
    buffers: [{ byteLength: packed.length },
      { byteLength: raw.byteLength, extensions: { EXT_meshopt_compression: { fallback: true } } }],
    bufferViews: [{ buffer: 1, byteLength: raw.byteLength, byteStride: 12,
      extensions: { EXT_meshopt_compression: { buffer: 0, byteLength: packed.length, byteStride: 12,
        count: 6, mode: "ATTRIBUTES" } } }],
    accessors: [{ bufferView: 0, componentType: 5126, count: 6, type: "VEC3" }],
  };
  change?.(doc, packed);
  const bin = new ArrayBuffer((packed.length + 3) & ~3);
  new Uint8Array(bin).set(packed);
  return encodeGlb(doc, bin);
}

test("decodes real synthetic meshoptimizer bytes without changing the default refusal", async () => {
  const data = await fixture();
  assert.throws(() => openStaticGlb(data), /GLB/);
  const preview = await openMeshoptPreview(data);
  try {
    assert.deepEqual(preview.metrics, { vertices: 6, triangles: 2, dimensions: [1, 2, 3] });
    assert.deepEqual(Array.from(preview.geometry.getAttribute("position").array), coords);
  } finally { preview.dispose(); }
});

test("rejects unsupported descriptors, decompressed budgets, range overflows and corrupted or invalid decoded data", async () => {
  const descriptor = (doc: any) => doc.bufferViews[0].extensions.EXT_meshopt_compression;
  for (const edit of [
    (doc: any) => { doc.buffers[0].uri = "https://example.invalid/no"; },
    (doc: any) => { descriptor(doc).byteOffset = 4; },
    (doc: any) => { descriptor(doc).byteLength += 1; },
    (doc: any) => { descriptor(doc).count = 2 ** 42; },
    (doc: any) => { descriptor(doc).count = 99999; doc.accessors[0].count = 99999; },
    (doc: any) => { descriptor(doc).filter = "OCTAHEDRAL"; },
    (doc: any) => { descriptor(doc).byteStride = 24; },
    (doc: any) => { doc.bufferViews[0].byteLength = 73; },
    (doc: any) => { doc.accessors[0].count = 3; },
    (doc: any) => { doc.extensionsUsed.push("KHR_texture_basisu"); },
    (_doc: any, packed: Uint8Array) => { packed[0] = 0; },
  ]) await assert.rejects(openMeshoptPreview(await fixture(edit)), /GLB/);
  await assert.rejects(openMeshoptPreview(await fixture(undefined, [NaN, ...coords.slice(1)])), /坐标/);
});
