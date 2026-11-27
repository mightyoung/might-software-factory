import { MeshoptEncoder } from "meshoptimizer/encoder";
import { encodeGlb } from "./glbFixture.ts";

export const coords = [0,0,0, 1,0,0, 0,2,0, 0,0,0, 1,0,0, 0,0,3];
export async function meshoptFixture(change?: (doc: any, packed: Uint8Array) => void, values = coords) {
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

