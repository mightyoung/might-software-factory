import { Group, Mesh, Vector3, type BufferGeometry, type MeshStandardMaterial } from "three";
import type { ParametricEquipment } from "../schema/scene.ts";

type Resources = { box: BufferGeometry; cylinder: BufferGeometry; frame: MeshStandardMaterial; signal: MeshStandardMaterial };
type Part = Mesh<BufferGeometry, MeshStandardMaterial>;

/** Build owned parts from shared unit assets. Ports are in local metres; the caller owns disposal. */
export function buildParametricEquipment({ kind, dimensions: { length: l, width: w, height: h }, rotationYRad }: ParametricEquipment, resources: Resources) {
  const group = new Group();
  const signals: Part[] = [];
  const ports = {
    inPort: new Vector3(-l / 2, h * 0.7, 0),
    outPort: new Vector3(l / 2, h * 0.7, 0),
  };
  group.rotation.y = rotationYRad;
  function part(geometry: keyof Pick<Resources, "box" | "cylinder">, scales: [number, number, number],
    position: [number, number, number], signal = false, rotation = 0) {
    const mesh = new Mesh(resources[geometry], signal ? resources.signal : resources.frame);
    mesh.scale.set(...scales);
    mesh.position.set(...position);
    mesh.rotation.y = rotation;
    group.add(mesh);
    if (signal) signals.push(mesh);
  }

  switch (kind) {
    case "CONVEYOR": {
      const belt = Math.min(h * 0.24, 0.18);
      const leg = Math.min(l * 0.12, w * 0.2, 0.12);
      const supports = Math.ceil(l / 1.5) + 1;
      part("box", [l, belt, w], [0, h - belt / 2, 0], true);
      for (let i = 0; i < supports; i++) {
        const x = -l / 2 + leg / 2 + (l - leg) * i / (supports - 1);
        for (const side of [-1, 1]) part("box", [leg, h - belt, leg], [x, (h - belt) / 2, side * (w - leg) / 2]);
      }
      ports.inPort.y = ports.outPort.y = h;
      break;
    }
    case "CORRIDOR": {
      const floor = Math.min(0.08, h * 0.2);
      const post = Math.min(0.08, l * 0.14, w * 0.15);
      const supports = Math.ceil(l / 1.5) + 1;
      part("box", [l, floor, w], [0, floor / 2, 0]);
      for (const side of [-1, 1]) {
        const z = side * (w - post) / 2;
        part("box", [l, post, post], [0, h - post / 2, z], true);
        for (let i = 0; i < supports; i++) {
          const x = -l / 2 + post / 2 + (l - post) * i / (supports - 1);
          part("cylinder", [post, h - floor, post], [x, (h + floor) / 2, z]);
        }
      }
      ports.inPort.y = ports.outPort.y = floor;
      break;
    }
    case "ROBOT_ARM": {
      const diameter = Math.min(l, w), section = diameter * 0.19;
      part("cylinder", [diameter * 0.75, h * 0.18, diameter * 0.75], [0, h * 0.09, 0]);
      part("box", [section, h * 0.72, section], [0, h * 0.54, 0]);
      part("box", [l * 0.54, h * 0.15, section], [l * 0.22, h * 0.85, 0], true);
      ports.inPort.set(0, 0, 0);
      ports.outPort.set(l * 0.49, h * 0.85, 0);
      break;
    }
    case "SPIRAL_CONVEYOR": {
      const radius = Math.min(l, w) * 0.34;
      const segments = Math.ceil(h / 1.1) * 8;
      const thickness = Math.min(h * 0.2, 0.08);
      part("cylinder", [radius, h, radius], [0, h / 2, 0]);
      for (let i = 0; i <= segments; i++) {
        const angle = i * Math.PI / 4;
        part("box", [radius * 0.7, thickness, radius * 0.52],
          [Math.cos(angle) * radius, thickness / 2 + (h - thickness) * i / segments, Math.sin(angle) * radius], true, -angle);
      }
      ports.inPort.set(radius, thickness, 0);
      ports.outPort.set(radius, h, 0);
      break;
    }
  }
  return { group, ports, signals };
}
