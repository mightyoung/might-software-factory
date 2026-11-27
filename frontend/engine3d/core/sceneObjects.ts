import { BoxGeometry, CylinderGeometry, Group, InstancedMesh, Matrix4, Mesh, MeshStandardMaterial, type BufferGeometry, type Vector3 } from "three";
import { buildParametricEquipment } from "./ParametricEquipmentFactory.ts";
import type { DeviceState, SceneDocument } from "../schema/scene.ts";

export type SceneBuildMode = "studio" | "player-readonly";
type PrimitiveDevice = Extract<SceneDocument["devices"][number], { primitive: string }>;

// Keeps culling local for distributed factory layouts rather than creating
// a single player-wide bounding volume for every compatible material.
const cell = (value: number) => Math.floor((value + 32) / 64);
const batchKey = ({ primitive, state, position }: PrimitiveDevice) => `${primitive}:${state}:${position.map(cell).join(",")}`;

export function buildSceneObjects(document: SceneDocument, mode: SceneBuildMode = "studio") {
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
  const batches = new Map<string, PrimitiveDevice[]>();
  if (mode === "player-readonly") for (const device of document.devices) {
    if (!("primitive" in device)) continue;
    const key = batchKey(device);
    const batch = batches.get(key) ?? [];
    batch.push(device);
    batches.set(key, batch);
  }
  const instanced: InstancedMesh[] = [];
  const transform = new Matrix4();
  for (const batch of batches.values()) {
    if (batch.length < 2) continue;
    const { primitive, state } = batch[0];
    const mesh = new InstancedMesh(primitive === "box" ? box : cylinder, materials[state], batch.length);
    for (const [index, device] of batch.entries()) {
      const [x, y, z] = device.position;
      const centre = y + device.size[1] / 2;
      transform.makeScale(...device.size).setPosition(x, centre, z);
      mesh.setMatrixAt(index, transform);
      // Non-rendering per-device anchors preserve exact selection/focus coordinates.
      const anchor = new Group();
      anchor.name = device.id;
      anchor.position.set(x, centre, z);
      group.add(anchor);
      devices.set(device.id, anchor);
    }
    mesh.instanceMatrix.needsUpdate = true;
    mesh.computeBoundingSphere();
    instanced.push(mesh);
    group.add(mesh);
  }
  for (const device of document.devices) {
    if (devices.has(device.id)) continue;
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
      if (mode === "player-readonly") return false;
      const parts = stateParts.get(id);
      if (!parts) return false;
      for (const part of parts) part.material = materials[state];
      return true;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      group.clear();
      for (const mesh of instanced) mesh.dispose();
      instanced.length = 0;
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
