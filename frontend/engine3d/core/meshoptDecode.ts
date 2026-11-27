import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";
import { z } from "zod";
import { isMeshoptBytes, MESHOPT_INPUT, MESHOPT_JSON as MAX_JSON, MESHOPT_SOURCE as MAX_SOURCE } from "./meshoptProtocol.ts";

// Offline codec qualification only. Deliberately not registered as a Studio/Player importer.
const extension = "EXT_meshopt_compression";
const stride = 12;
const count = z.number().int().min(3).max(65_535).multipleOf(3);
const sourceLength = z.number().int().min(1).max(MAX_SOURCE);
const decodedLength = z.number().int().min(36).max(65_535 * stride);
const model = z.strictObject({
  asset: z.strictObject({ version: z.literal("2.0") }),
  extensionsRequired: z.tuple([z.literal(extension)]),
  extensionsUsed: z.tuple([z.literal(extension)]),
  scene: z.literal(0).optional(),
  scenes: z.tuple([z.strictObject({ nodes: z.tuple([z.literal(0)]) })]),
  nodes: z.tuple([z.strictObject({ mesh: z.literal(0) })]),
  meshes: z.tuple([z.strictObject({ primitives: z.tuple([
    z.strictObject({ attributes: z.strictObject({ POSITION: z.literal(0) }), mode: z.literal(4).optional() }),
  ]) })]),
  buffers: z.tuple([
    z.strictObject({ byteLength: sourceLength }),
    z.strictObject({ byteLength: decodedLength,
      extensions: z.strictObject({ [extension]: z.strictObject({ fallback: z.literal(true) }) }).optional() }),
  ]),
  bufferViews: z.tuple([z.strictObject({
    buffer: z.literal(1), byteOffset: z.literal(0).optional(), byteLength: decodedLength,
    byteStride: z.literal(stride), target: z.literal(34962).optional(),
    extensions: z.strictObject({ [extension]: z.strictObject({
      buffer: z.literal(0), byteOffset: z.literal(0).optional(), byteLength: sourceLength, byteStride: z.literal(stride),
      count, mode: z.literal("ATTRIBUTES"), filter: z.literal("NONE").optional(),
    }) }),
  })]),
  accessors: z.tuple([z.strictObject({
    bufferView: z.literal(0), byteOffset: z.literal(0).optional(), count,
    componentType: z.literal(5126), type: z.literal("VEC3"),
  })]),
});

const invalid = () => new Error("GLB：不符合试验性 Meshopt 静态坐标格式或预算。");
function preflight(data: ArrayBuffer) {
  if (!isMeshoptBytes(data, MESHOPT_INPUT)) throw invalid();
  const view = new DataView(data);
  const uint = (offset: number) => view.getUint32(offset, true);
  const jsonLength = uint(12), header = 20 + jsonLength;
  if (uint(0) !== 0x46546c67 || uint(4) !== 2 || uint(8) !== data.byteLength || uint(16) !== 0x4e4f534a
    || jsonLength < 4 || jsonLength > MAX_JSON || jsonLength % 4 || header + 8 > data.byteLength) throw invalid();
  const binLength = uint(header), binStart = header + 8;
  if (uint(header + 4) !== 0x004e4942 || binLength % 4 || binStart + binLength !== data.byteLength) throw invalid();
  let document: unknown;
  try { document = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(new Uint8Array(data, 20, jsonLength))); }
  catch { throw invalid(); }
  const parsed = model.safeParse(document);
  if (!parsed.success) throw invalid();
  const { buffers, bufferViews: [buffer], accessors: [accessor] } = parsed.data;
  const compressed = buffer.extensions[extension];
  // Both factors are independently bounded by the whitelist; sums never trust the container.
  const bytes = compressed.count * stride;
  const source = buffers[0].byteLength;
  if (accessor.count !== compressed.count || buffer.byteLength !== bytes || buffers[1].byteLength !== bytes
    || compressed.byteLength !== source || source > binLength || binLength - source > 3
    || new Uint8Array(data, binStart + source, binLength - source).some((value) => value !== 0)) throw invalid();
  return { encoded: new Uint8Array(data, binStart, source).slice(), bytes, count: accessor.count };
}

export async function decodeMeshoptPositions(data: ArrayBuffer) {
  const { encoded, bytes, count } = preflight(data);
  if (!MeshoptDecoder.supported) throw new Error("GLB：此环境不支持 Meshopt 解码。");
  const decoded = {
    asset: { version: "2.0" }, scenes: [{ nodes: [0] }], nodes: [{ mesh: 0 }],
    meshes: [{ primitives: [{ attributes: { POSITION: 0 } }] }],
    buffers: [{ byteLength: bytes }], bufferViews: [{ buffer: 0, byteLength: bytes }],
    accessors: [{ bufferView: 0, count, componentType: 5126, type: "VEC3" }],
  };
  const json = new TextEncoder().encode(JSON.stringify(decoded));
  const size = Math.ceil(json.length / 4) * 4, header = 20 + size;
  const flat = new ArrayBuffer(header + 8 + bytes);
  const target = new Uint8Array(flat);
  const view = new DataView(flat);
  [0x46546c67, 2, flat.byteLength, size, 0x4e4f534a].forEach((value, index) => view.setUint32(index * 4, value, true));
  target.fill(0x20, 20, header);
  target.set(json, 20);
  view.setUint32(header, bytes, true);
  view.setUint32(header + 4, 0x004e4942, true);
  try {
    await MeshoptDecoder.ready;
    MeshoptDecoder.decodeGltfBuffer(target.subarray(header + 8), count, stride, encoded, "ATTRIBUTES", "NONE");
  } catch { throw new Error("GLB：Meshopt 坐标解码失败。"); }
  return flat;
}
