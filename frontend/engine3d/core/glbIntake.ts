import { BufferAttribute, BufferGeometry, Matrix3, Matrix4, Quaternion, Vector3 } from "three";
import { z } from "zod";

export const MAX_GLB_BYTES = 8 * 1024 * 1024;
const integer = z.number().int().min(0).max(MAX_GLB_BYTES);
const MAX_MESHES = 4;
const MAX_VERTICES = 100_000;
const MAX_TRIANGLE_INDICES = 450_000;
const accessorIndex = z.number().int().min(0).max(MAX_MESHES * 3 - 1);
const meshIndex = z.number().int().min(0).max(MAX_MESHES - 1);
const name = z.string().max(160).optional();
const coordinate = z.number().finite().min(-10000).max(10000);
const vec3 = z.tuple([coordinate, coordinate, coordinate]);
const unit = z.number().min(0).max(1);
const nodeIndex = z.number().int().min(0).max(15);
const docSchema = z.strictObject({
  asset: z.strictObject({ version: z.literal("2.0"), generator: name, copyright: name }),
  scene: z.literal(0).optional(),
  scenes: z.tuple([z.strictObject({ nodes: z.array(nodeIndex).min(1).max(16), name })]),
  nodes: z.array(z.strictObject({
    mesh: meshIndex.optional(), name, children: z.array(nodeIndex).min(1).max(16).optional(),
    matrix: z.array(coordinate).length(16).optional(), translation: vec3.optional(),
    rotation: z.tuple([coordinate, coordinate, coordinate, coordinate]).optional(), scale: vec3.optional(),
  })).min(1).max(16),
  meshes: z.array(z.strictObject({ primitives: z.tuple([z.strictObject({
    attributes: z.strictObject({ POSITION: accessorIndex, NORMAL: accessorIndex.optional() }),
    indices: accessorIndex.optional(), mode: z.literal(4).optional(), material: meshIndex.optional(),
  })]), name })).min(1).max(MAX_MESHES),
  materials: z.array(z.strictObject({
    name, alphaMode: z.literal("OPAQUE").optional(), doubleSided: z.boolean().optional(),
    emissiveFactor: z.tuple([unit, unit, unit]).optional(),
    pbrMetallicRoughness: z.strictObject({
      baseColorFactor: z.tuple([unit, unit, unit, unit]).optional(), metallicFactor: unit.optional(),
      roughnessFactor: unit.optional(),
    }).optional(),
  })).min(1).max(MAX_MESHES).optional(),
  buffers: z.tuple([z.strictObject({ byteLength: integer, name })]),
  bufferViews: z.array(z.strictObject({
    buffer: z.literal(0), byteOffset: integer.optional(), byteLength: integer,
    byteStride: z.number().int().min(4).max(252).multipleOf(4).optional(),
    target: z.union([z.literal(34962), z.literal(34963)]).optional(), name,
  })).min(1).max(MAX_MESHES * 3),
  accessors: z.array(z.strictObject({
    bufferView: accessorIndex, byteOffset: integer.optional(), componentType: z.number().int(),
    count: z.number().int().min(1).max(MAX_TRIANGLE_INDICES), type: z.enum(["VEC3", "SCALAR"]),
    min: z.array(z.number().finite()).min(1).max(3).optional(),
    max: z.array(z.number().finite()).min(1).max(3).optional(), name,
  })).min(1).max(MAX_MESHES * 3),
});

export interface StaticGlb {
  geometry: BufferGeometry;
  metrics: { vertices: number; triangles: number; dimensions: [number, number, number] };
  dispose(): void;
}

const reject = (reason: string): never => { throw new Error("GLB：" + reason); };
type GlbDocument = z.infer<typeof docSchema>;

function meshTransforms(doc: GlbDocument): Matrix4[] {
  const transforms: Matrix4[] = [];
  const seen = new Set<number>();
  function visit(index: number, parent: Matrix4) {
    const node = doc.nodes[index];
    if (!node || seen.has(index)) return reject("场景层级含缺失节点、循环或多重父节点。");
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
    const world = parent.clone().multiply(local);
    if (world.elements.some((v) => !Number.isFinite(v) || Math.abs(v) > 10000) || world.determinant() < 1e-9)
      return reject("组合变换超出范围。");
    if (node.mesh !== undefined) {
      if (!doc.meshes[node.mesh] || transforms[node.mesh]) return reject("需要互不重复的有效网格引用。");
      transforms[node.mesh] = world;
    }
    if (node.mesh === undefined && !node.children) return reject("场景包含无网格的空叶节点。");
    for (const child of node.children ?? []) visit(child, world);
  }
  const identity = new Matrix4();
  for (const root of doc.scenes[0].nodes) visit(root, identity);
  if (seen.size !== doc.nodes.length || doc.meshes.some((_, index) => !transforms[index]))
    return reject("存在不可达节点或未使用网格。");
  return transforms;
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
  if (!parsed.success) return reject("当前仅支持有界的无纹理静态网格，不支持外链、贴图或压缩扩展。");
  const doc = parsed.data;
  const declared = doc.buffers[0].byteLength;
  if (declared > binBytes || binBytes - declared > 3) reject("实际缓冲区与声明长度不符。");
  const transforms = meshTransforms(doc);
  const primitives = doc.meshes.map(({ primitives: [primitive] }) => primitive);
  const accessorIds = primitives.flatMap(({ attributes, indices }) =>
    [attributes.POSITION, attributes.NORMAL, indices].filter((id) => id !== undefined));
  const materialIds = new Set(primitives.flatMap(({ material }) => material === undefined ? [] : [material]));
  if (doc.accessors.length !== accessorIds.length || new Set(accessorIds).size !== accessorIds.length ||
      new Set(doc.accessors.map((a) => a.bufferView)).size !== doc.bufferViews.length ||
      materialIds.size !== (doc.materials?.length ?? 0) || Array.from(materialIds).some((id) => !doc.materials?.[id]))
    reject("访问器或材质与网格不匹配。");
  const declaredCounts = primitives.map(({ attributes, indices }) => {
    const v = doc.accessors[attributes.POSITION];
    const i = indices === undefined ? v : doc.accessors[indices];
    if (!v || !i) return reject("网格引用的访问器不存在。");
    return { vertices: v.count, indices: i.count };
  });
  const vertices = declaredCounts.reduce((sum, count) => sum + count.vertices, 0);
  const totalIndices = declaredCounts.reduce((sum, count) => sum + count.indices, 0);
  if (vertices > MAX_VERTICES) reject("总顶点不得超过 100,000。");
  if (totalIndices > MAX_TRIANGLE_INDICES) reject("总三角面不得超过 150,000。");
  if (declaredCounts.some((count) => count.indices < 3 || count.indices % 3 !== 0))
    reject("三角面顶点数量无效。");
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

  const positions = new Float32Array(vertices * 3);
  const normals = primitives.some(({ attributes }) => attributes.NORMAL !== undefined)
    ? new Float32Array(vertices * 3) : undefined;
  const indexed = primitives.length > 1 || primitives[0].indices !== undefined;
  const indexData = indexed ? new Uint32Array(totalIndices) : undefined;
  const rangesWithNormals: [number, number][] = [];
  const mins = [Infinity, Infinity, Infinity], maxs = [-Infinity, -Infinity, -Infinity];
  const position = new Vector3();
  const normal = new Vector3();
  const validPosition = (value: number) => Number.isFinite(value) && Math.abs(value) <= 10000;
  const viewOwners = new Map<number, number>();
  let vertexOffset = 0, indexOffset = 0;
  for (const [mesh, primitive] of primitives.entries()) {
    const world = transforms[mesh];
    const p = doc.accessors[primitive.attributes.POSITION];
    if (p.type !== "VEC3" || p.componentType !== 5126) return reject("坐标须为三维浮点数。");
    const loc = read(primitive.attributes.POSITION, 12, 4, 34962);
    const normalIndex = primitive.attributes.NORMAL;
    for (const index of [primitive.attributes.POSITION, normalIndex, primitive.indices]) {
      if (index === undefined) continue;
      const view = doc.accessors[index]?.bufferView;
      if (view === undefined || (viewOwners.has(view) && viewOwners.get(view) !== mesh))
        return reject("不同网格不能共享缓冲区视图。");
      viewOwners.set(view, mesh);
    }
    if (normalIndex !== undefined && normals) {
      const a = doc.accessors[normalIndex];
      if (a.type !== "VEC3" || a.componentType !== 5126 || a.count !== p.count)
        return reject("法线须与顶点一一对应。");
      const n = read(normalIndex, 12, 4, 34962);
      if (p.bufferView === a.bufferView) {
        if (doc.bufferViews[p.bufferView].byteStride === undefined) return reject("共享顶点视图需要显式步长。");
        if (loc.offset < n.end && n.offset < loc.end) {
          const offset = Math.abs(loc.offset - n.offset) % loc.stride;
          if (offset < 12 || loc.stride - offset < 12) return reject("交错的坐标和法线重叠。");
        }
      }
      const matrix = new Matrix3().getNormalMatrix(world);
      const start = vertexOffset * 3;
      for (let i = 0; i < n.count; i++) {
        const at = n.offset + i * n.stride;
        normal.set(f.getFloat32(at, true), f.getFloat32(at + 4, true), f.getFloat32(at + 8, true));
        if (!Number.isFinite(normal.lengthSq()) || Math.abs(normal.lengthSq() - 1) > 0.01)
          return reject("法线须为有限单位向量。");
        normal.applyNormalMatrix(matrix).toArray(normals, start + i * 3);
      }
      rangesWithNormals.push([start, start + n.count * 3]);
    }
    if (primitive.indices !== undefined && indexData) {
      const a = doc.accessors[primitive.indices];
      if (a.type !== "SCALAR" || doc.accessors.some((other) => other !== a && other.bufferView === a.bufferView) ||
          ![5121, 5123, 5125].includes(a.componentType)) return reject("索引须为独立缓冲区内的无符号整数。");
      const bytes = a.componentType === 5121 ? 1 : a.componentType === 5123 ? 2 : 4;
      const ix = read(primitive.indices, bytes, bytes, 34963);
      for (let i = 0; i < ix.count; i++) {
        const offset = ix.offset + i * bytes;
        const value = bytes === 1 ? f.getUint8(offset) : bytes === 2 ? f.getUint16(offset, true) : uint(offset);
        if (value >= loc.count) reject("三角面引用了不存在的顶点。");
        indexData[indexOffset + i] = vertexOffset + value;
      }
    } else if (indexData) {
      for (let i = 0; i < loc.count; i++) indexData[indexOffset + i] = vertexOffset + i;
    }
    for (let i = 0; i < loc.count; i++) {
      const at = loc.offset + i * loc.stride;
      position.set(f.getFloat32(at, true), f.getFloat32(at + 4, true), f.getFloat32(at + 8, true));
      if (!validPosition(position.x) || !validPosition(position.y) || !validPosition(position.z))
        return reject("模型局部坐标无效或超出范围。");
      position.applyMatrix4(world);
      if (!validPosition(position.x) || !validPosition(position.y) || !validPosition(position.z))
        return reject("变换后的坐标无效或超出范围。");
      const pos = (vertexOffset + i) * 3;
      position.toArray(positions, pos);
      for (let axis = 0; axis < 3; axis++) {
        mins[axis] = Math.min(mins[axis], positions[pos + axis]);
        maxs[axis] = Math.max(maxs[axis], positions[pos + axis]);
      }
    }
    vertexOffset += loc.count;
    indexOffset += declaredCounts[mesh].indices;
  }
  const dimensions: [number, number, number] = [maxs[0] - mins[0], maxs[1] - mins[1], maxs[2] - mins[2]];
  if (dimensions.filter((v) => v > 0.00001).length < 2) reject("网格没有可预览的空间范围。");
  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new BufferAttribute(positions, 3));
  if (indexData) geometry.setIndex(new BufferAttribute(indexData, 1));
  if (normals && rangesWithNormals.length === primitives.length) {
    geometry.setAttribute("normal", new BufferAttribute(normals, 3));
  } else {
    geometry.computeVertexNormals();
    if (normals) {
      const computed = geometry.getAttribute("normal").array as Float32Array;
      for (const [start, end] of rangesWithNormals) computed.set(normals.subarray(start, end), start);
    }
  }
  geometry.computeBoundingBox();
  let disposed = false;
  return { geometry, metrics: { vertices, triangles: totalIndices / 3, dimensions },
    dispose() { if (!disposed) { disposed = true; geometry.dispose(); } } };
}
