import { BoxGeometry, CylinderGeometry, Group, Mesh, MeshStandardMaterial } from "three";
import type { DeviceState, SceneDocument } from "../schema/scene.ts";

export function buildSceneObjects(document: SceneDocument) {
  const group = new Group();
  const box = new BoxGeometry();
  const cylinder = new CylinderGeometry(0.5, 0.5, 1, 24);
  const materials = {
    normal: new MeshStandardMaterial({ color: 0x247c72 }),
    warning: new MeshStandardMaterial({ color: 0xd89413 }),
    alarm: new MeshStandardMaterial({ color: 0xa83243 }),
  };
  const devices = new Map<string, Mesh<BoxGeometry | CylinderGeometry, MeshStandardMaterial>>();
  for (const device of document.devices) {
    const mesh = new Mesh(device.primitive === "box" ? box : cylinder, materials[device.state]);
    const [x, y, z] = device.position;
    mesh.position.set(x, y + device.size[1] / 2, z);
    mesh.scale.set(...device.size);
    mesh.name = device.id;
    group.add(mesh);
    devices.set(device.id, mesh);
  }
  let disposed = false;
  return {
    group,
    devices,
    setState(id: string, state: DeviceState) {
      const mesh = devices.get(id);
      if (!mesh) return false;
      mesh.material = materials[state];
      return true;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      group.clear();
      devices.clear();
      box.dispose();
      cylinder.dispose();
      for (const material of Object.values(materials)) material.dispose();
    },
  };
}
