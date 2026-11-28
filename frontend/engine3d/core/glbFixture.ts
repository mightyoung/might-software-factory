// Test-owned synthetic GLB: a tetrahedron, never a real plant asset.
export function syntheticGlb(change?: (json: Record<string, any>, bin: DataView) => void): ArrayBuffer {
  const bin = new ArrayBuffer(72);
  new Float32Array(bin, 0, 12).set([0, 0, 0, 2, 0, 0, 1, 0, 2, 1, 2, 1]);
  new Uint16Array(bin, 48, 12).set([0, 1, 2, 0, 3, 1, 1, 3, 2, 2, 3, 0]);
  const json = {
    asset: { version: "2.0" }, scene: 0, scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 }, indices: 1 }] }],
    buffers: [{ byteLength: 72 }],
    bufferViews: [{ buffer: 0, byteLength: 48 }, { buffer: 0, byteOffset: 48, byteLength: 24 }],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 4, type: "VEC3", min: [0,0,0], max: [2,2,2] },
      { bufferView: 1, componentType: 5123, count: 12, type: "SCALAR" },
    ],
  };
  change?.(json, new DataView(bin));
  const encoded = new TextEncoder().encode(JSON.stringify(json));
  const jsonLength = Math.ceil(encoded.length / 4) * 4;
  const data = new ArrayBuffer(28 + jsonLength + bin.byteLength);
  const view = new DataView(data);
  [0x46546c67, 2, data.byteLength, jsonLength, 0x4e4f534a].forEach((value, index) => view.setUint32(index * 4, value, true));
  const bytes = new Uint8Array(data);
  bytes.fill(0x20, 20, 20 + jsonLength);
  bytes.set(encoded, 20);
  view.setUint32(20 + jsonLength, bin.byteLength, true);
  view.setUint32(24 + jsonLength, 0x004e4942, true);
  bytes.set(new Uint8Array(bin), 28 + jsonLength);
  return data;
}
