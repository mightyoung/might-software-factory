import { z } from "zod";
import { parametricEquipmentSchema, SceneDocumentSchema } from "../schema/scene.ts";

const stableId = z.string().regex(/^[a-z][a-z0-9_-]{0,63}$/);
const local = z.number().finite().min(-36).max(36);
const anchor = z.strictObject({
  id: stableId,
  position: z.tuple([local, local, local]).readonly(),
}).readonly();
const prefab = z.strictObject({
  id: stableId,
  name: z.string().trim().min(1).max(80),
  equipment: parametricEquipmentSchema,
  state: z.enum(["normal", "warning", "alarm"]),
  anchors: z.array(anchor).max(8).readonly(),
}).refine(({ anchors, equipment }) => {
  if (new Set(anchors.map(({ id }) => id)).size !== anchors.length) return false;
  const { length, width, height } = equipment.dimensions;
  return anchors.every(({ position: [x, y, z] }) =>
    Math.abs(x) <= length / 2 && Math.abs(z) <= width / 2 && y >= 0 && y <= height);
});

type Parsed = z.infer<typeof prefab>;
export type PrefabVersion = Readonly<Omit<Parsed, "equipment"> & {
  version: number;
  equipment: Readonly<Omit<Parsed["equipment"], "dimensions"> & {
    dimensions: Readonly<Parsed["equipment"]["dimensions"]>;
  }>;
}>;

export function createPrefabBank() {
  const entries = new Map<string, PrefabVersion[]>();
  return {
    save(draft: unknown): PrefabVersion {
      const result = prefab.safeParse(draft);
      if (!result.success) throw new RangeError("组件标识、尺寸、状态或锚点无效；锚点须在设备边界内且名称唯一。");
      const validated = result.data;
      const history = entries.get(validated.id) ?? [];
      if (!history.length && entries.size >= 12) throw new RangeError("本页最多保存 12 个组件。");
      if (history.length >= 8) throw new RangeError("每个组件最多保存 8 个版本。");
      const snapshot: PrefabVersion = Object.freeze({
        ...validated, version: history.length + 1,
        equipment: Object.freeze({
          ...validated.equipment, dimensions: Object.freeze({ ...validated.equipment.dimensions }),
        }),
      });
      history.push(snapshot);
      entries.set(snapshot.id, history);
      return snapshot;
    },
    get(id: string, version: number) { return entries.get(id)?.[version - 1]; },
    list() { return Array.from(entries.values()).flatMap((history) => history.slice().reverse()); },
  };
}

/** Projects an immutable reusable definition to a new validated scene instance.
 * Anchors remain authored device-local metadata; no placement side effects or bindings.
 */
export function prefabToDevice(
  template: PrefabVersion, placement: { id: string; position: [number, number, number] },
) {
  const scene = SceneDocumentSchema.parse({ schemaVersion: 1, screenId: "prefab-projection", devices: [{
    ...placement, label: template.name, state: template.state, equipment: template.equipment,
  }] });
  const device = scene.devices[0];
  if (!("equipment" in device)) throw new Error("组件实例投影缺少设备。");
  return device;
}
