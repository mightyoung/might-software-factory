import { BoxGeometry, CylinderGeometry, Group, Mesh, MeshStandardMaterial, type BufferGeometry, type Vector3 } from "three";
import { buildParametricEquipment } from "./ParametricEquipmentFactory.ts";
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
  const frame = new MeshStandardMaterial({ color: 0x46565a, roughness: 0.65 });
  type Part = Mesh<BufferGeometry, MeshStandardMaterial>;
  const devices = new Map<string, Group | Part>();
  const ports = new Map<string, { inPort: Vector3; outPort: Vector3 }>();
  const stateParts = new Map<string, Part[]>();
  for (const device of document.devices) {
    const [x, y, z] = device.position;
    if ("equipment" in device) {
      const built = buildParametricEquipment(device.equipment, { box, cylinder, frame, signal: materials[device.state] });
      built.group.name = device.id;
      built.group.position.set(x, y, z);
      group.add(built.group);
      devices.set(device.id, built.group);
      ports.set(device.id, built.ports);
      stateParts.set(device.id, built.signals);
    } else {
      const mesh = new Mesh(device.primitive === "box" ? box : cylinder, materials[device.state]);
      mesh.position.set(x, y + device.size[1] / 2, z);
      mesh.scale.set(...device.size);
      mesh.name = device.id;
      group.add(mesh);
      devices.set(device.id, mesh);
      stateParts.set(device.id, [mesh]);
    }
  }
  let disposed = false;
  return {
    group,
    devices,
    ports,
    setState(id: string, state: DeviceState) {
      const parts = stateParts.get(id);
      if (!parts) return false;
      for (const part of parts) part.material = materials[state];
      return true;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      group.clear();
      devices.clear();
      ports.clear();
      stateParts.clear();
      frame.dispose();
      box.dispose();
      cylinder.dispose();
      for (const material of Object.values(materials)) material.dispose();
    },
  };
}
