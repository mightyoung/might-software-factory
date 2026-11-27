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
  return encodeGlb(json, bin);
}

export function exporterGlb(change?: (json: Record<string, any>, bin: DataView) => void): ArrayBuffer {
  const bin = new ArrayBuffer(108);
  new Float32Array(bin, 0, 24).set([
    0, 0, 0, 0, 1, 0,
    1, 0, 0, 0, 1, 0,
    0, 2, 0, 0, 1, 0,
    0, 0, 3, 0, 1, 0,
  ]);
  new Uint16Array(bin, 96, 6).set([0, 1, 2, 0, 3, 1]);
  const json = {
    asset: { version: "2.0" }, scene: 0, scenes: [{ nodes: [0] }],
    nodes: [{ rotation: [0, Math.SQRT1_2, 0, Math.SQRT1_2], children: [1] }, { mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0, NORMAL: 1 }, indices: 2, material: 0 }] }],
    materials: [{ pbrMetallicRoughness: { baseColorFactor: [0.2, 0.5, 0.8, 1], metallicFactor: 0.1, roughnessFactor: 0.6 } }],
    buffers: [{ byteLength: 108 }],
    bufferViews: [{ buffer: 0, byteLength: 96, byteStride: 24, target: 34962 },
      { buffer: 0, byteOffset: 96, byteLength: 12, target: 34963 }],
    accessors: [
      { bufferView: 0, componentType: 5126, count: 4, type: "VEC3" },
      { bufferView: 0, byteOffset: 12, componentType: 5126, count: 4, type: "VEC3" },
      { bufferView: 1, componentType: 5123, count: 6, type: "SCALAR" },
    ],
  };
  change?.(json, new DataView(bin));
  return encodeGlb(json, bin);
}

export function encodeGlb(json: unknown, bin: ArrayBuffer): ArrayBuffer {
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

export function multiMeshGlb(change?: (json: Record<string, any>) => void, counts = [3, 3], indexCounts = [3, 0]): ArrayBuffer {
  const first = 12 * counts[0], second = 12 * counts[1];
  const firstIndices = indexCounts[0] * 2;
  const secondIndices = indexCounts[1] * 2;
  const other = (first + firstIndices + 3) & ~3;
  const normalOffset = other + second;
  const secondIndexOffset = normalOffset + first;
  const bin = new ArrayBuffer((secondIndexOffset + secondIndices + 3) & ~3);
  new Float32Array(bin, 0, 9).set([0, 0, 0, 1, 0, 0, 0, 2, 0]);
  new Float32Array(bin, other, 9).set([0, 0, 0, 1, 0, 0, 0, 0, 3]);
  const normals = new Float32Array(bin, normalOffset, first / 4);
  for (let i = 0; i < normals.length; i += 3) normals[i] = 1;
  new Uint16Array(bin, first, 3).set([0, 1, 2]);
  const secondPrimitive: { attributes: Record<string, number>; indices?: number } = { attributes: { POSITION: 2 } };
  const json = {
    asset: { version: "2.0" }, scene: 0, scenes: [{ nodes: [0] }],
    nodes: [{ children: [1, 2] }, { mesh: 0 }, { mesh: 1, translation: [4, 0, 0] }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0, NORMAL: 3 }, indices: 1 }] }, { primitives: [secondPrimitive] }],
    buffers: [{ byteLength: bin.byteLength }],
    bufferViews: [{ buffer: 0, byteLength: first }, { buffer: 0, byteOffset: first, byteLength: firstIndices },
      { buffer: 0, byteOffset: other, byteLength: second }, { buffer: 0, byteOffset: normalOffset, byteLength: first }],
    accessors: [
      { bufferView: 0, componentType: 5126, count: counts[0], type: "VEC3" },
      { bufferView: 1, componentType: 5123, count: indexCounts[0], type: "SCALAR" },
      { bufferView: 2, componentType: 5126, count: counts[1], type: "VEC3" },
      { bufferView: 3, componentType: 5126, count: counts[0], type: "VEC3" },
    ],
  };
  if (secondIndices) {
    secondPrimitive.indices = 4;
    json.bufferViews.push({ buffer: 0, byteOffset: secondIndexOffset, byteLength: secondIndices });
    json.accessors.push({ bufferView: 4, componentType: 5123, count: indexCounts[1], type: "SCALAR" });
  }
  change?.(json);
  return encodeGlb(json, bin);
}
