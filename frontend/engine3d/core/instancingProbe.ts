import { BoxGeometry, Group, InstancedMesh, Matrix4, Mesh, MeshBasicMaterial } from "three";

export const MAX_PROBE_DEVICES = 500;
export type ProbeMode = "individual" | "instanced";

/** Both modes render identical geometry, poses and material.
 * Culling is disabled solely for this fixed comparison so every requested object is counted.
 */
export function createInstancingFixture(mode: ProbeMode, count: number) {
  if ((mode !== "individual" && mode !== "instanced") ||
      !Number.isInteger(count) || count < 1 || count > MAX_PROBE_DEVICES)
    throw new RangeError("探针仅支持 1–500 个合成方块。");
  const group = new Group(), geometry = new BoxGeometry(), material = new MeshBasicMaterial({ color: 0x247c72 });
  const release = { geometry: 0, material: 0, instance: 0 };
  geometry.addEventListener("dispose", () => release.geometry++);
  material.addEventListener("dispose", () => release.material++);
  const instance = mode === "instanced" ? new InstancedMesh(geometry, material, count) : null;
  if (instance) {
    instance.addEventListener("dispose", () => release.instance++);
    instance.frustumCulled = false;
    group.add(instance);
  }
  const transform = new Matrix4();
  for (let i = 0; i < count; i++) {
    const x = (i % 25) * 2 - 24;
    const z = Math.floor(i / 25) * 2 - 19;
    if (instance) {
      instance.setMatrixAt(i, transform.makeTranslation(x, 0.5, z));
    } else {
      const mesh = new Mesh(geometry, material);
      mesh.frustumCulled = false;
      mesh.position.set(x, 0.5, z);
      group.add(mesh);
    }
  }
  if (instance) instance.instanceMatrix.needsUpdate = true;
  let disposed = false;
  return { group, releaseCounts: () => ({ ...release }), dispose() {
    if (disposed) return;
    disposed = true;
    group.removeFromParent();
    group.clear();
    instance?.dispose();
    geometry.dispose();
    material.dispose();
  } };
}
