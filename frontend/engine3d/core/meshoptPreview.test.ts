import assert from "node:assert/strict";
import { test } from "node:test";
import { openStaticGlb } from "./glbIntake.ts";
import { openMeshoptPreview } from "./meshoptPreview.ts";

import { coords, meshoptFixture } from "./meshoptFixture.ts";

test("decodes real synthetic meshoptimizer bytes without changing the default refusal", async () => {
  const data = await meshoptFixture();
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
  ]) await assert.rejects(openMeshoptPreview(await meshoptFixture(edit)), /GLB/);
  await assert.rejects(openMeshoptPreview(await meshoptFixture(undefined, [NaN, ...coords.slice(1)])), /坐标/);
});
