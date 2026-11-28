import { equipmentDimensionLimits, type ParametricEquipment } from "../schema/scene.ts";

export const resizeFaces = ["+X", "-X", "+Z", "-Z", "+Y"] as const;
export type ResizeFace = typeof resizeFaces[number];

export function resizeEquipment(equipment: ParametricEquipment, face: ResizeFace, outwardMeters: number) {
  if (!resizeFaces.includes(face) || !Number.isFinite(outwardMeters) || Math.abs(outwardMeters) > 1000) {
    throw new RangeError("无效的设备缩放范围。");
  }
  const axis = face[1] === "X" ? "length" : face[1] === "Z" ? "width" : "height";
  const current = equipment.dimensions[axis];
  // Snap the gesture in local metres before applying bounds. Use the actual,
  // clamped result to keep the opposite face fixed.
  const proposed = current + Math.sign(outwardMeters) * Math.round(Math.abs(outwardMeters) * 20) / 20;
  const next = Number(Math.max(equipmentDimensionLimits.min, Math.min(equipmentDimensionLimits[axis], proposed)).toFixed(6));
  const offset = (face[0] === "-" ? -1 : 1) * (next - current) / 2;
  const c = Math.cos(equipment.rotationYRad), s = Math.sin(equipment.rotationYRad);
  const world: [number, number, number] = axis === "length" ? [offset * c, 0, -offset * s]
    : axis === "width" ? [offset * s, 0, offset * c] : [0, 0, 0];
  return { dimensions: { ...equipment.dimensions, [axis]: next }, positionDelta: world.map((v) => v || 0) as [number, number, number] };
}
