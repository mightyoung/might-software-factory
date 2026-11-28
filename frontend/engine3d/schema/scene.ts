import { z } from "zod";

const stableId = z.string().regex(/^[a-z][a-z0-9_-]{0,63}$/);
const coordinate = z.number().finite().min(-1000).max(1000);
const size = z.number().finite().min(0.05).max(100);
const device = z.strictObject({
  id: stableId,
  label: z.string().trim().min(1).max(80),
  primitive: z.enum(["box", "cylinder"]),
  position: z.tuple([coordinate, coordinate, coordinate]),
  size: z.tuple([size, size, size]),
  state: z.enum(["normal", "warning", "alarm"]),
});

export const SceneDocumentSchema = z.strictObject({
  schemaVersion: z.literal(1),
  screenId: stableId,
  devices: z.array(device).max(500),
}).refine(({ devices }) => new Set(devices.map(({ id }) => id)).size === devices.length, {
  path: ["devices"], message: "Duplicate device ID",
});
export type SceneDocument = z.infer<typeof SceneDocumentSchema>;
export type DeviceState = SceneDocument["devices"][number]["state"];
