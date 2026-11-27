import { prefabToDevice, type PrefabVersion } from "./prefabBank.ts";
import { SceneDocumentSchema, type SceneDocument } from "../schema/scene.ts";

export type FloorMetres = readonly [number, number];
export type PrefabPlacement = Readonly<{ prefabId: string; version: number; deviceId: string; metres: FloorMetres }>;

/** Append a copied device from an exact version, not a live lookup into the bank. */
export function placePrefab(scene: SceneDocument, template: PrefabVersion, deviceId: string, metres: FloorMetres) {
  const [x, z] = metres;
  if (![x, z].every((value) => Number.isFinite(value) && Math.abs(value) <= 10))
    throw new RangeError("放置坐标必须在 ±10 米内。");
  if (scene.devices.some((device) => device.id === deviceId)) throw new RangeError("实例标识已存在。");
  if (scene.devices.filter((device) => "equipment" in device).length >= 24)
    throw new RangeError("场景最多支持 24 台参数化设备。");
  const device = prefabToDevice(template, { id: deviceId, position: [x, 0, z] });
  const next = SceneDocumentSchema.safeParse({ ...scene, devices: [...scene.devices, device] });
  if (!next.success) throw new RangeError("场景容量或组件实例标识不合法。");
  const placement: PrefabPlacement = Object.freeze({
    prefabId: template.id, version: template.version, deviceId, metres: Object.freeze([x, z]) as FloorMetres,
  });
  return { scene: next.data, device, placement };
}
