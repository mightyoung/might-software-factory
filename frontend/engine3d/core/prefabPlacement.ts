import { prefabToDevice, type PrefabVersion } from "./prefabBank.ts";
import { SceneDocumentSchema, type SceneDocument } from "../schema/scene.ts";

export type FloorMetres = readonly [number, number];
export type PrefabPlacement = Readonly<{ prefabId: string; version: number; deviceId: string; metres: FloorMetres }>;

function copyMetres([x, z]: FloorMetres): FloorMetres {
  if (![x, z].every((value) => Number.isFinite(value) && Math.abs(value) <= 10))
    throw new RangeError("放置坐标必须在 ±10 米内。");
  return Object.freeze([x, z]);
}

/** Append a copied device from an exact version, not a live lookup into the bank. */
export function placePrefab(scene: SceneDocument, template: PrefabVersion, deviceId: string, metres: FloorMetres) {
  const coordinates = copyMetres(metres);
  const [x, z] = coordinates;
  if (scene.devices.some((device) => device.id === deviceId)) throw new RangeError("实例标识已存在。");
  if (scene.devices.filter((device) => "equipment" in device).length >= 24)
    throw new RangeError("场景最多支持 24 台参数化设备。");
  const device = prefabToDevice(template, { id: deviceId, position: [x, 0, z] });
  const next = SceneDocumentSchema.safeParse({ ...scene, devices: [...scene.devices, device] });
  if (!next.success) throw new RangeError("场景容量或组件实例标识不合法。");
  const placement: PrefabPlacement = Object.freeze({
    prefabId: template.id, version: template.version, deviceId, metres: coordinates,
  });
  return { scene: next.data, device, placement };
}

function findPinnedIndex(scene: SceneDocument, placement: PrefabPlacement) {
  const index = scene.devices.findIndex(({ id }) => id === placement.deviceId);
  const device = scene.devices[index];
  if (!device || !("equipment" in device) || device.position[1] !== 0
    || device.position[0] !== placement.metres[0] || device.position[2] !== placement.metres[1])
    throw new RangeError("当前场景中找不到匹配的已放置实例。");
  return index;
}

/** Update a session-pinned instance, keeping its identity and existing geometry. */
export function relocatePrefab(scene: SceneDocument, placement: PrefabPlacement, metres: FloorMetres) {
  const coordinates = copyMetres(metres);
  const [x, z] = coordinates;
  const index = findPinnedIndex(scene, placement);
  const devices = scene.devices.map((entry, i) => i === index
    ? { ...entry, position: [x, 0, z] } : entry);
  const next = SceneDocumentSchema.parse({ ...scene, devices });
  return { scene: next, device: next.devices[index], placement: Object.freeze({ ...placement, metres: coordinates }) };
}

/** Permit only removal of this live instance, never of a tour target or a template. */
export function removePrefab(scene: SceneDocument, placement: PrefabPlacement,
  tour: readonly { deviceId: string }[]) {
  const index = findPinnedIndex(scene, placement);
  if (tour.some(({ deviceId }) => deviceId === placement.deviceId))
    throw new Error("此实例仍被巡检机位使用。请先单独移除对应机位，再移除实例。");
  return SceneDocumentSchema.parse({ ...scene, devices: scene.devices.filter((_, i) => i !== index) });
}
