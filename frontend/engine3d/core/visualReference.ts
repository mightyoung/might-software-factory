import { Group, Mesh, MeshBasicMaterial, Vector3 } from "three";
import type { StaticGlb } from "./glbIntake.ts";
import type { SourceUnit } from "./modelUnits.ts";
import { PreviewSlot } from "./PreviewSlot.ts";

export type ReferencePoint = readonly [number, number];

export function createVisualReferenceSlot() {
  const group = new Group();
  group.name = "ephemeral-verified-reference";
  const material = new MeshBasicMaterial({ color: 0x884719, wireframe: true });
  const storage = new PreviewSlot();
  let disposed = false;
  function clear() {
    group.clear();
    storage.clear();
  }
  return {
    group, clear,
    replace(model: StaticGlb, unit: SourceUnit, [x, z]: ReferencePoint) {
      if (disposed || model === storage.current) throw new Error("此模型引用当前不可替换。");
      if (![x, z].every((v) => Number.isFinite(v) && Math.abs(v) <= 10))
        throw new RangeError("参考坐标必须在 ±10 米内。");
      const bounds = model.geometry.boundingBox;
      if (!bounds || (unit !== "m" && unit !== "mm")) throw new Error("参考模型缺少有效单位或边界。");
      const factor = unit === "mm" ? 0.001 : 1;
      const size = bounds.getSize(new Vector3()).multiplyScalar(factor);
      if (![size.x, size.y, size.z].every((v) => Number.isFinite(v) && v >= 0) ||
          Math.max(size.x, size.y, size.z) > 20 || Math.max(size.x, size.y, size.z) < 0.001)
        throw new RangeError("参考模型最大边必须在 0.001 至 20 米内。");
      const center = bounds.getCenter(new Vector3()).multiplyScalar(factor);
      const mesh = new Mesh(model.geometry, material);
      mesh.scale.setScalar(factor);
      mesh.position.set(x - center.x, -bounds.min.y * factor, z - center.z);
      clear();
      storage.replace(model);
      group.add(mesh);
      return mesh;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      clear();
      group.removeFromParent();
      material.dispose();
    },
  };
}
