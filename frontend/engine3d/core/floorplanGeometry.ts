import { BoxGeometry, Group, Mesh, MeshStandardMaterial } from "three";
import type { Point } from "./floorplan.ts";

export type FloorplanGeometrySnapshot = {
  walls: readonly { start: Point; end: Point }[];
};

export function buildFloorplanGeometry({ walls }: FloorplanGeometrySnapshot) {
  const group = new Group();
  const geometry = new BoxGeometry(1, 1, 1);
  const material = new MeshStandardMaterial({ color: 0x758999, roughness: 0.8 });
  for (const { start: [ax, az], end: [bx, bz] } of walls) {
    const dx = (bx - ax) / 10, dz = (bz - az) / 10;
    const mesh = new Mesh(geometry, material);
    // Draft coordinates are decimetres; the engine uses metres.
    mesh.position.set((ax + bx) / 20, 1.5, (az + bz) / 20);
    mesh.rotation.y = -Math.atan2(dz, dx);
    mesh.scale.set(Math.hypot(dx, dz), 3, 0.2);
    group.add(mesh);
  }
  return { group, dispose() { group.clear(); geometry.dispose(); material.dispose(); } };
}
