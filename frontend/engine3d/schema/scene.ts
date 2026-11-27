import { z } from "zod";

const stableId = z.string().regex(/^[a-z][a-z0-9_-]{0,63}$/);
const coordinate = z.number().finite().min(-1000).max(1000);
const size = z.number().finite().min(0.05).max(100);
export const equipmentDimensionLimits = { min: 0.2, length: 36, width: 12, height: 12 } as const;
const dimension = z.number().finite().min(equipmentDimensionLimits.min);
export const parametricEquipmentSchema = z.strictObject({
  kind: z.enum(["CONVEYOR", "CORRIDOR", "ROBOT_ARM", "SPIRAL_CONVEYOR"]),
  dimensions: z.strictObject({
    length: dimension.max(equipmentDimensionLimits.length),
    width: dimension.max(equipmentDimensionLimits.width),
    height: dimension.max(equipmentDimensionLimits.height),
  }),
  rotationYRad: z.number().finite().min(-Math.PI * 2).max(Math.PI * 2).default(0),
});
const base = {
  id: stableId,
  label: z.string().trim().min(1).max(80),
  position: z.tuple([coordinate, coordinate, coordinate]),
  state: z.enum(["normal", "warning", "alarm"]),
};
const device = z.union([
  z.strictObject({ ...base, primitive: z.enum(["box", "cylinder"]), size: z.tuple([size, size, size]) }),
  z.strictObject({ ...base, equipment: parametricEquipmentSchema }),
]);
export type ParametricEquipment = z.infer<typeof parametricEquipmentSchema>;

export const SceneDocumentSchema = z.strictObject({
  schemaVersion: z.literal(1),
  screenId: stableId,
  devices: z.array(device).max(500),
}).refine(({ devices }) => new Set(devices.map(({ id }) => id)).size === devices.length, {
  path: ["devices"], message: "Duplicate device ID",
}).refine(({ devices }) => devices.filter((device) => "equipment" in device).length <= 24, {
  path: ["devices"], message: "At most 24 parametric devices per preview",
});
export type SceneDocument = z.infer<typeof SceneDocumentSchema>;
export type DeviceState = SceneDocument["devices"][number]["state"];
