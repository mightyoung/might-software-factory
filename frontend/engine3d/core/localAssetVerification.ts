import { z } from "zod";
import { localAssetDigest, validLocalAssetName, type LocalAssetMetadata } from "./LocalAssetBank.ts";
import { MAX_GLB_BYTES, openStaticGlb } from "./glbIntake.ts";
import { inspectPreviewUnits } from "./modelUnits.ts";

export const MAX_MANIFEST_BYTES = 16 * 1024;
const dimension = z.number().finite().min(0).max(20_000);
const extents = z.tuple([dimension, dimension, dimension]);
const asset = z.strictObject({
  id: z.string().regex(/^[0-9a-f]{64}$/),
  name: z.string().refine(validLocalAssetName),
  size: z.number().int().min(28).max(MAX_GLB_BYTES),
  sourceUnit: z.enum(["m", "mm"]),
  dimensionsMetres: extents,
  checkUnits: z.boolean(),
  metrics: z.strictObject({
    vertices: z.number().int().min(3).max(100_000),
    triangles: z.number().int().min(1).max(150_000),
    dimensions: extents,
  }),
});
const manifest = z.strictObject({
  format: z.literal("might-local-asset-metadata-v1"),
  assets: z.array(asset).min(1).max(8),
});
const equal = (a: readonly number[], b: readonly number[]) => a.length === b.length && a.every((v, i) => v === b[i]);

/** Verify content consistency, not authorship or access rights. No asset is registered or rendered. */
export async function verifyLocalAssetReference(raw: string, source: ArrayBuffer): Promise<LocalAssetMetadata> {
  if (raw.length > MAX_MANIFEST_BYTES || new TextEncoder().encode(raw).byteLength > MAX_MANIFEST_BYTES)
    throw new RangeError("模型清单不得超过 16 KiB。");
  let json: unknown;
  try { json = JSON.parse(raw); }
  catch { throw new Error("模型清单不是有效 JSON。"); }
  const result = manifest.safeParse(json);
  if (!result.success) throw new Error("模型清单不符合本地元数据 v1 契约。");
  const entries = result.data.assets;
  if (new Set(entries.map(({ id }) => id)).size !== entries.length)
    throw new Error("模型清单包含重复的 SHA-256。");
  if (entries.reduce((total, { size }) => total + size, 0) > 32 * 1024 * 1024)
    throw new Error("模型清单超过 32 MiB 会话容量。");
  if (source.byteLength < 28 || source.byteLength > MAX_GLB_BYTES) throw new RangeError("GLB 文件超出校验范围。");
  const bytes = source.slice(0);
  const id = await localAssetDigest(bytes);
  const record = entries.find((entry) => entry.id === id);
  if (!record) throw new Error("模型 SHA-256 与清单中的任何条目都不匹配。");
  const model = openStaticGlb(bytes);
  try {
    const { vertices, triangles, dimensions } = model.metrics;
    const units = inspectPreviewUnits(dimensions, record.sourceUnit);
    if (record.size !== bytes.byteLength || record.metrics.vertices !== vertices ||
        record.metrics.triangles !== triangles || !equal(record.metrics.dimensions, dimensions) ||
        !equal(record.dimensionsMetres, units.meters) || record.checkUnits !== units.checkUnits)
      throw new Error("清单指标与实际模型不一致。");
    return record;
  } finally { model.dispose(); }
}
