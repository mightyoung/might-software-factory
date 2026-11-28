import { BufferAttribute, BufferGeometry, Matrix3, Matrix4, Quaternion, Vector3 } from "three";
import { z } from "zod";

export const MAX_GLB_BYTES = 8 * 1024 * 1024;
const integer = z.number().int().min(0).max(MAX_GLB_BYTES);
const accessorIndex = z.number().int().min(0).max(2);
const name = z.string().max(160).optional();
const coordinate = z.number().finite().min(-10000).max(10000);
const vec3 = z.tuple([coordinate, coordinate, coordinate]);
const unit = z.number().min(0).max(1);
const nodeIndex = z.number().int().min(0).max(15);
const docSchema = z.strictObject({
  asset: z.strictObject({ version: z.literal("2.0"), generator: name, copyright: name }),
  scene: z.literal(0).optional(),
  scenes: z.tuple([z.strictObject({ nodes: z.tuple([nodeIndex]), name })]),
  nodes: z.array(z.strictObject({
    mesh: z.literal(0).optional(), name, children: z.tuple([nodeIndex]).optional(),
    matrix: z.array(coordinate).length(16).optional(), translation: vec3.optional(),
    rotation: z.tuple([coordinate, coordinate, coordinate, coordinate]).optional(), scale: vec3.optional(),
  })).min(1).max(16),
  meshes: z.tuple([z.strictObject({ primitives: z.tuple([z.strictObject({
    attributes: z.strictObject({ POSITION: accessorIndex, NORMAL: accessorIndex.optional() }),
    indices: accessorIndex.optional(), mode: z.literal(4).optional(), material: z.literal(0).optional(),
  })]), name })]),
  materials: z.tuple([z.strictObject({
    name, alphaMode: z.literal("OPAQUE").optional(), doubleSided: z.boolean().optional(),
    emissiveFactor: z.tuple([unit, unit, unit]).optional(),
    pbrMetallicRoughness: z.strictObject({
      baseColorFactor: z.tuple([unit, unit, unit, unit]).optional(), metallicFactor: unit.optional(),
      roughnessFactor: unit.optional(),
    }).optional(),
  })]).optional(),
  buffers: z.tuple([z.strictObject({ byteLength: integer, name })]),
  bufferViews: z.array(z.strictObject({
    buffer: z.literal(0), byteOffset: integer.optional(), byteLength: integer,
    byteStride: z.number().int().min(4).max(252).multipleOf(4).optional(),
    target: z.union([z.literal(34962), z.literal(34963)]).optional(), name,
  })).min(1).max(3),
  accessors: z.array(z.strictObject({
    bufferView: accessorIndex, byteOffset: integer.optional(), componentType: z.number().int(),
    count: z.number().int().min(1).max(450000), type: z.enum(["VEC3", "SCALAR"]),
    min: z.array(z.number().finite()).min(1).max(3).optional(),
    max: z.array(z.number().finite()).min(1).max(3).optional(), name,
  })).min(1).max(3),
});

export interface StaticGlb {
  geometry: BufferGeometry;
  metrics: { vertices: number; triangles: number; dimensions: [number, number, number] };
  dispose(): void;
}

const reject = (reason: string): never => { throw new Error("GLB：" + reason); };
type GlbDocument = z.infer<typeof docSchema>;

function worldTransform(doc: GlbDocument): Matrix4 {
  const world = new Matrix4();
  const seen = new Set<number>();
  let index = doc.scenes[0].nodes[0];
  while (true) {
    const node = doc.nodes[index];
    if (!node || seen.has(index)) return reject("场景层级含缺失节点或循环。");
    seen.add(index);
    const local = new Matrix4();
    if (node.matrix) {
      const m = node.matrix;
      if (node.translation || node.rotation || node.scale || m[3] !== 0 || m[7] !== 0 || m[11] !== 0 || m[15] !== 1) {
        return reject("变换矩阵必须为独立仿射变换。");
      }
      const basis = [0, 4, 8].map((i) => new Vector3(m[i], m[i + 1], m[i + 2]));
      if (basis.some((v) => v.length() < 0.001 || v.length() > 1000)) return reject("缩放超出范围。");
      basis.forEach((v) => v.normalize());
      if (Math.abs(basis[0].dot(basis[1])) > 1e-5 || Math.abs(basis[0].dot(basis[2])) > 1e-5 ||
          Math.abs(basis[1].dot(basis[2])) > 1e-5 ||
          basis[0].clone().cross(basis[1]).dot(basis[2]) <= 0) return reject("不支持剪切或镜像矩阵。");
      local.fromArray(m);
    } else {
      const rotation = new Quaternion(...(node.rotation ?? [0, 0, 0, 1]));
      const scale = node.scale ?? [1, 1, 1];
      if (Math.abs(rotation.lengthSq() - 1) > 1e-5 || scale.some((v) => v < 0.001 || v > 1000))
        return reject("需要单位旋转和范围内的正缩放。");
      local.compose(new Vector3(...(node.translation ?? [0, 0, 0])), rotation.normalize(), new Vector3(...scale));
    }
    world.multiply(local);
    if (world.elements.some((v) => !Number.isFinite(v) || Math.abs(v) > 10000) || world.determinant() < 1e-9)
      return reject("组合变换超出范围。");
    if (node.mesh !== undefined) {
      if (node.children || seen.size !== doc.nodes.length) return reject("需要以唯一网格结尾的单链层级。");
      return world;
    }
    if (!node.children) return reject("场景没有可预览网格。");
    index = node.children[0];
  }
}

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
  if (!parsed.success) return reject("当前仅支持无纹理的单静态网格，不支持外链、贴图或压缩扩展。");
  const doc = parsed.data;
  const declared = doc.buffers[0].byteLength;
  if (declared > binBytes || binBytes - declared > 3) reject("实际缓冲区与声明长度不符。");
  const primitive = doc.meshes[0].primitives[0];
  const world = worldTransform(doc);
  const accessorIds = [primitive.attributes.POSITION, primitive.attributes.NORMAL, primitive.indices].filter((id) => id !== undefined);
  if (doc.accessors.length !== accessorIds.length || new Set(accessorIds).size !== accessorIds.length ||
      new Set(doc.accessors.map((a) => a.bufferView)).size !== doc.bufferViews.length ||
      (primitive.material === undefined) !== (doc.materials === undefined)) reject("访问器或材质与网格不匹配。");
  for (let i = 0; i < doc.bufferViews.length; i++) {
    const a = doc.bufferViews[i], start = a.byteOffset ?? 0;
    for (const b of doc.bufferViews.slice(i + 1)) {
      const other = b.byteOffset ?? 0;
      if (start < other + b.byteLength && other < start + a.byteLength) reject("缓冲区视图重叠。");
    }
  }
  function read(index: number, bytesPerElement: number, alignment: number, target: number) {
    const a = doc.accessors[index];
    if (!a) return reject("访问器不存在。");
    const view = doc.bufferViews[a.bufferView];
    if (!view || (view.target !== undefined && view.target !== target)) return reject("缓冲区类型不符。");
    const base = view.byteOffset ?? 0, relative = a.byteOffset ?? 0;
    const stride = view.byteStride ?? bytesPerElement;
    const length = (a.count - 1) * stride + bytesPerElement;
    if (stride < bytesPerElement || (target === 34963 && view.byteStride !== undefined) ||
        relative % alignment || (base + relative) % alignment || base + view.byteLength > declared ||
        relative + length > view.byteLength) reject("网格数据越界或未对齐。");
    return { offset: binStart + base + relative, count: a.count, stride, end: binStart + base + relative + length };
  }

  const p = doc.accessors[primitive.attributes.POSITION];
  if (!p || p.type !== "VEC3" || p.componentType !== 5126 || p.count > 100000)
    return reject("坐标须为有限三维浮点数，顶点不得超过 100,000。");
  const loc = read(primitive.attributes.POSITION, 12, 4, 34962);
  const normalIndex = primitive.attributes.NORMAL;
  let normals: Float32Array | undefined;
  if (normalIndex !== undefined) {
    const a = doc.accessors[normalIndex];
    if (!a || a.type !== "VEC3" || a.componentType !== 5126 || a.count !== p.count)
      return reject("法线须与顶点一一对应。");
    const n = read(normalIndex, 12, 4, 34962);
    if (p.bufferView === a.bufferView) {
      if (doc.bufferViews[p.bufferView].byteStride === undefined) return reject("共享顶点视图需要显式步长。");
      if (loc.offset < n.end && n.offset < loc.end) {
        const offset = Math.abs(loc.offset - n.offset) % loc.stride;
        if (offset < 12 || loc.stride - offset < 12) return reject("交错的坐标和法线重叠。");
      }
    }
    normals = new Float32Array(n.count * 3);
    const normal = new Vector3();
    const matrix = new Matrix3().getNormalMatrix(world);
    for (let i = 0; i < n.count; i++) {
      const at = n.offset + i * n.stride;
      normal.set(f.getFloat32(at, true), f.getFloat32(at + 4, true), f.getFloat32(at + 8, true));
      if (!Number.isFinite(normal.lengthSq()) || Math.abs(normal.lengthSq() - 1) > 0.01)
        return reject("法线须为有限单位向量。");
      normal.applyNormalMatrix(matrix).toArray(normals, i * 3);
    }
  }
  let indexData: Uint32Array | undefined;
  let triangleIndices = loc.count;
  if (primitive.indices !== undefined) {
    const a = doc.accessors[primitive.indices];
    if (!a || a === p || a.type !== "SCALAR" || doc.accessors.some((other) => other !== a && other.bufferView === a.bufferView) || ![5121, 5123, 5125].includes(a.componentType))
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
  const position = new Vector3();
  const validPosition = (value: number) => Number.isFinite(value) && Math.abs(value) <= 10000;
  for (let i = 0; i < loc.count; i++) {
    const at = loc.offset + i * loc.stride;
    const xyz = [f.getFloat32(at, true), f.getFloat32(at + 4, true), f.getFloat32(at + 8, true)] as const;
    if (!xyz.every(validPosition)) return reject("模型局部坐标无效或超出范围。");
    position.set(...xyz).applyMatrix4(world);
    if (!validPosition(position.x) || !validPosition(position.y) || !validPosition(position.z))
      return reject("变换后的坐标无效或超出范围。");
    position.toArray(positions, i * 3);
    for (let axis = 0; axis < 3; axis++) {
      const value = positions[i * 3 + axis];
      mins[axis] = Math.min(mins[axis], value);
      maxs[axis] = Math.max(maxs[axis], value);
    }
  }
  const dimensions: [number, number, number] = [maxs[0] - mins[0], maxs[1] - mins[1], maxs[2] - mins[2]];
  if (dimensions.filter((v) => v > 0.00001).length < 2) reject("网格没有可预览的空间范围。");
  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new BufferAttribute(positions, 3));
  if (indexData) geometry.setIndex(new BufferAttribute(indexData, 1));
  if (normals) geometry.setAttribute("normal", new BufferAttribute(normals, 3));
  else geometry.computeVertexNormals();
  geometry.computeBoundingBox();
  let disposed = false;
  return { geometry, metrics: { vertices: loc.count, triangles: triangleIndices / 3, dimensions },
    dispose() { if (!disposed) { disposed = true; geometry.dispose(); } } };
}
