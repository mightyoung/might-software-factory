import { z } from "zod";
import { validatePolygon, type FloorplanDraft } from "../core/floorplan.ts";
import { SceneDocumentSchema, type SceneDocument } from "./scene.ts";

export const MAX_STUDIO_BYTES = 256 * 1024;
const coord = z.number().int().min(-100).max(100);
const point = z.tuple([coord, coord]);
const id = z.number().int().min(1).max(10000);
const tourPoint = z.strictObject({
  deviceId: z.string().regex(/^[a-z][a-z0-9_-]{0,63}$/),
  label: z.string().trim().min(1).max(60),
  dwellSeconds: z.number().int().min(1).max(30),
});
export type TourPoint = z.infer<typeof tourPoint>;
export const hudKeys = ["devices", "alarms", "walls", "zones"] as const;
export type HudKey = typeof hudKeys[number];
export const hudLabels: Record<HudKey, string> = { devices: "设备", alarms: "告警", walls: "墙体", zones: "区域" };

const schema = z.strictObject({
  snapshotVersion: z.literal(1),
  scene: SceneDocumentSchema,
  cameraTour: z.array(tourPoint).max(20).optional(),
  hudWidgets: z.array(z.enum(hudKeys)).max(4).refine((keys) => new Set(keys).size === keys.length).optional(),
  floorplan: z.strictObject({
    walls: z.array(z.strictObject({ id, start: point, end: point })).max(100),
    zones: z.array(z.strictObject({ id, points: z.array(point).min(3).max(60) })).max(20),
  }),
});
type Snapshot = z.infer<typeof schema>;

export function parseStudioSnapshot(raw: string) {
  if (raw.length > MAX_STUDIO_BYTES || new TextEncoder().encode(raw).length > MAX_STUDIO_BYTES)
    throw new Error("场景文件不得超过 256 KiB。");
  let input: unknown;
  try { input = JSON.parse(raw); }
  catch { throw new Error("场景文件不是有效的 JSON。"); }
  const parsed = schema.safeParse(input);
  if (!parsed.success) throw new Error("场景版本、内容或坐标不符合 Studio v1 契约。");
  const value: Snapshot = parsed.data;
  const { walls, zones } = value.floorplan;
  if (value.cameraTour) {
    const deviceIds = new Set(value.scene.devices.map(({ id }) => id));
    const seen = new Set<string>();
    for (const point of value.cameraTour) {
      if (!deviceIds.has(point.deviceId) || seen.has(point.deviceId)) throw new Error("巡检机位包含不存在或重复的设备。");
      seen.add(point.deviceId);
    }
  }
  const ids = new Set<number>(), segments = new Set<string>();
  for (const shape of [...walls, ...zones]) {
    if (ids.has(shape.id)) throw new Error("平面形状标识重复。");
    ids.add(shape.id);
  }
  for (const wall of walls) {
    const a = wall.start.join(","), b = wall.end.join(",");
    const key = a < b ? a + ":" + b : b + ":" + a;
    if (a === b || segments.has(key)) throw new Error("墙段为零长或重复。");
    segments.add(key);
  }
  return { ...value, floorplan: { walls, zones: zones.map((zone) => ({ ...zone, area: validatePolygon(zone.points) })) } };
}

export function serializeStudioSnapshot(scene: SceneDocument, floorplan: ReturnType<FloorplanDraft["snapshot"]>,
  cameraTour: readonly TourPoint[] = [], hudWidgets?: readonly HudKey[]): string {
  const raw = JSON.stringify({ snapshotVersion: 1, scene, floorplan, ...(cameraTour.length ? { cameraTour } : {}),
    ...(hudWidgets ? { hudWidgets } : {}) }, null, 2);
  parseStudioSnapshot(raw);
  return raw;
}
