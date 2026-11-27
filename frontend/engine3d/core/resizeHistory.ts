import { SceneDocumentSchema, type ParametricEquipment, type SceneDocument } from "../schema/scene.ts";

type Transform = Readonly<{
  position: readonly [number, number, number];
  equipment: ParametricEquipment;
}>;
type Entry = { screen: string; id: string; before: Transform; after: Transform };
type Direction = "undo" | "redo";
function transform(scene: SceneDocument, id: string) {
  const device = scene.devices.find((item) => item.id === id);
  if (!device || !("equipment" in device)) return null;
  return { position: device.position, equipment: device.equipment };
}
function equal(a: Transform, b: Transform) {
  const { dimensions: left } = a.equipment, { dimensions: right } = b.equipment;
  return a.equipment.kind === b.equipment.kind && a.equipment.rotationYRad === b.equipment.rotationYRad
    && a.position.every((value, i) => value === b.position[i])
    && left.length === right.length && left.width === right.width && left.height === right.height;
}
export function createResizeHistory() {
  const undos: Entry[] = [], redos: Entry[] = [];
  function clear() { undos.length = redos.length = 0; }
  return {
    clear,
    canUndo: () => undos.length > 0,
    canRedo: () => redos.length > 0,
    record(original: SceneDocument, updated: SceneDocument, id: string) {
      if (original.screenId !== updated.screenId) return;
      const before = transform(original, id), after = transform(updated, id);
      if (!before || !after || equal(before, after)) return;
      redos.length = 0;
      undos.push(structuredClone({ screen: original.screenId, id, before, after }));
      if (undos.length > 20) undos.shift();
    },
    step(direction: Direction, scene: SceneDocument, commit: (next: SceneDocument) => boolean) {
      const [from, to] = direction === "undo" ? [undos, redos] : [redos, undos];
      const entry = from.at(-1);
      if (!entry) return null;
      const actual = transform(scene, entry.id);
      const [expected, replacement] = direction === "undo" ? [entry.after, entry.before] : [entry.before, entry.after];
      if (entry.screen !== scene.screenId || !actual || !equal(actual, expected)) { clear(); return null; }
      const next = SceneDocumentSchema.safeParse({
        ...scene, devices: scene.devices.map((device) => device.id === entry.id && "equipment" in device
          ? { ...device, position: replacement.position,
            equipment: { ...device.equipment, dimensions: replacement.equipment.dimensions } } : device),
      });
      if (!next.success || !commit(next.data)) return null;
      from.pop();
      to.push(entry);
      return { scene: next.data, id: entry.id };
    },
  };
}
