import { BufferAttribute, BufferGeometry } from "three";
import { z } from "zod";

export const MAX_GLB_BYTES = 8 * 1024 * 1024;
const integer = z.number().int().min(0).max(MAX_GLB_BYTES);
const accessorIndex = z.number().int().min(0).max(1);
const name = z.string().max(160).optional();
const docSchema = z.strictObject({
  asset: z.strictObject({ version: z.literal("2.0"), generator: name, copyright: name }),
  scene: z.literal(0).optional(),
  scenes: z.tuple([z.strictObject({ nodes: z.tuple([z.literal(0)]), name })]),
  nodes: z.tuple([z.strictObject({ mesh: z.literal(0), name })]),
  meshes: z.tuple([z.strictObject({ primitives: z.tuple([z.strictObject({
    attributes: z.strictObject({ POSITION: accessorIndex }), indices: accessorIndex.optional(), mode: z.literal(4).optional(),
  })]), name })]),
  buffers: z.tuple([z.strictObject({ byteLength: integer, name })]),
  bufferViews: z.array(z.strictObject({
    buffer: z.literal(0), byteOffset: integer.optional(), byteLength: integer,
    target: z.union([z.literal(34962), z.literal(34963)]).optional(), name,
  })).min(1).max(2),
  accessors: z.array(z.strictObject({
    bufferView: accessorIndex, byteOffset: integer.optional(), componentType: z.number().int(),
    count: z.number().int().min(1).max(450000), type: z.enum(["VEC3", "SCALAR"]),
    min: z.array(z.number().finite()).min(1).max(3).optional(),
    max: z.array(z.number().finite()).min(1).max(3).optional(), name,
  })).min(1).max(2),
});

export interface StaticGlb {
  geometry: BufferGeometry;
  metrics: { vertices: number; triangles: number; dimensions: [number, number, number] };
  dispose(): void;
}

const reject = (reason: string): never => { throw new Error("GLB：" + reason); };
export function openStaticGlb(data: ArrayBuffer): StaticGlb {
  if (data.byteLength < 28 || data.byteLength > MAX_GLB_BYTES) reject("文件大小不在预览范围内。");
  const f = new DataView(data);
  const uint = (offset: number) => f.getUint32(offset, true);
  const jsonBytes = uint(12);
  const binHeader = 20 + jsonBytes;
  if (uint(0) !== 0x46546c67 || uint(4) !== 2 || uint(8) !== data.byteLength ||
      uint(16) !== 0x4e4f534a || jsonBytes < 4 || jsonBytes > 64 * 1024 || jsonBytes % 4 ||
      binHeader + 8 > data.byteLength) reject("文件头或 JSON 分块无效。");
  const binBytes = uint(binHeader);
  const binStart = binHeader + 8;
  if (uint(binHeader + 4) !== 0x004e4942 || binBytes % 4 || binStart + binBytes !== data.byteLength)
    reject("需要且只允许一个完整的内嵌二进制分块。");

  let json: unknown;
  try { json = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(new Uint8Array(data, 20, jsonBytes))); }
  catch { reject("无法读取 JSON。"); }
  const parsed = docSchema.safeParse(json);
  if (!parsed.success) return reject("当前仅支持无纹理的单静态网格，不支持外链、材质、变换或压缩扩展。");
  const doc = parsed.data;
  const declared = doc.buffers[0].byteLength;
  if (declared > binBytes || binBytes - declared > 3) reject("实际缓冲区与声明长度不符。");
  const primitive = doc.meshes[0].primitives[0];
  if (doc.accessors.length !== (primitive.indices === undefined ? 1 : 2) || doc.bufferViews.length !== doc.accessors.length)
    reject("访问器数量与网格不匹配。");
  function read(index: number, bytesPerElement: number, alignment: number, target: number) {
    const a = doc.accessors[index];
    if (!a) return reject("访问器不存在。");
    const view = doc.bufferViews[a.bufferView];
    if (!view || (view.target !== undefined && view.target !== target)) return reject("缓冲区类型不符。");
    const base = view.byteOffset ?? 0, relative = a.byteOffset ?? 0;
    const length = a.count * bytesPerElement;
    if ((base + relative) % alignment || base + view.byteLength > declared || relative + length > view.byteLength)
      reject("网格数据越界或未对齐。");
    return { offset: binStart + base + relative, count: a.count };
  }

  const p = doc.accessors[primitive.attributes.POSITION];
  if (!p || p.type !== "VEC3" || p.componentType !== 5126 || p.count > 100000)
    return reject("坐标须为有限三维浮点数，顶点不得超过 100,000。");
  const loc = read(primitive.attributes.POSITION, 12, 4, 34962);
  let indexData: Uint32Array | undefined;
  let triangleIndices = loc.count;
  if (primitive.indices !== undefined) {
    const a = doc.accessors[primitive.indices];
    if (!a || a === p || a.type !== "SCALAR" || a.bufferView === p.bufferView || ![5121, 5123, 5125].includes(a.componentType))
      return reject("索引须为独立缓冲区内的无符号整数。");
    const bytes = a.componentType === 5121 ? 1 : a.componentType === 5123 ? 2 : 4;
    const ix = read(primitive.indices, bytes, bytes, 34963);
    triangleIndices = ix.count;
    if (triangleIndices < 3 || triangleIndices % 3) reject("三角面索引数量无效。");
    indexData = new Uint32Array(triangleIndices);
    for (let i = 0; i < triangleIndices; i++) {
      const offset = ix.offset + i * bytes;
      const value = bytes === 1 ? f.getUint8(offset) : bytes === 2 ? f.getUint16(offset, true) : uint(offset);
      if (value >= loc.count) reject("三角面引用了不存在的顶点。");
      indexData[i] = value;
    }
  }
  if (triangleIndices < 3 || triangleIndices % 3) reject("三角面顶点数量无效。");

  const positions = new Float32Array(loc.count * 3);
  const mins = [Infinity, Infinity, Infinity], maxs = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < positions.length; i++) {
    const value = f.getFloat32(loc.offset + i * 4, true);
    if (!Number.isFinite(value) || Math.abs(value) > 10000) reject("模型坐标无效或超出范围。");
    positions[i] = value;
    const axis = i % 3;
    mins[axis] = Math.min(mins[axis], value);
    maxs[axis] = Math.max(maxs[axis], value);
  }
  const dimensions: [number, number, number] = [maxs[0] - mins[0], maxs[1] - mins[1], maxs[2] - mins[2]];
  if (dimensions.filter((v) => v > 0.00001).length < 2) reject("网格没有可预览的空间范围。");
  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new BufferAttribute(positions, 3));
  if (indexData) geometry.setIndex(new BufferAttribute(indexData, 1));
  geometry.computeVertexNormals();
  geometry.computeBoundingBox();
  let disposed = false;
  return { geometry, metrics: { vertices: loc.count, triangles: triangleIndices / 3, dimensions },
    dispose() { if (!disposed) { disposed = true; geometry.dispose(); } } };
}
