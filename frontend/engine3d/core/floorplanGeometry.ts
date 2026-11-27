import { BoxGeometry, Group, Mesh, MeshStandardMaterial } from "three";
import type { Point } from "./floorplan.ts";

export interface FloorplanWallSnapshot {
  readonly id: number;
  /** X/Z endpoints in integer decimetres. */
  readonly start: Point;
  readonly end: Point;
}

export interface FloorplanGeometrySnapshot {
  readonly walls: readonly FloorplanWallSnapshot[];
}

const WALL_HEIGHT_M = 3;
const WALL_THICKNESS_M = 0.2;

function copyPoint(value: Point): Point {
  if (!Array.isArray(value) || value.length !== 2 ||
      !Number.isInteger(value[0]) || !Number.isInteger(value[1]) ||
      Math.abs(value[0]) > 100 || Math.abs(value[1]) > 100)
    throw new RangeError("墙段顶点必须为 ±10 米内的整分米坐标。");
  return Object.freeze([value[0] || 0, value[1] || 0]) as Point;
}

function snapshotWalls(source: FloorplanGeometrySnapshot): FloorplanGeometrySnapshot {
  if (!Array.isArray(source.walls) || source.walls.length > 100)
    throw new RangeError("最多支持 100 条墙段。");
  const ids = new Set<number>();
  const segments = new Set<string>();
  const walls = source.walls.map((wall) => {
    if (!wall || !Number.isSafeInteger(wall.id) || wall.id < 1 || ids.has(wall.id))
      throw new RangeError("墙段标识必须为唯一正整数。");
    const start = copyPoint(wall.start), end = copyPoint(wall.end);
    const a = start.join(","), b = end.join(",");
    const key = a < b ? a + ":" + b : b + ":" + a;
    if (a === b || segments.has(key)) throw new RangeError("墙段不能为零长或重复。");
    ids.add(wall.id);
    segments.add(key);
    return Object.freeze({ id: wall.id, start, end });
  });
  return Object.freeze({ walls: Object.freeze(walls) });
}

type WallMesh = Mesh<BoxGeometry, MeshStandardMaterial>;

export interface AuthoredFloorplanGeometry {
  readonly snapshot: FloorplanGeometrySnapshot;
  readonly group: Group;
  readonly walls: ReadonlyMap<number, WallMesh>;
  dispose(): void;
}

/** Builds authored wall prisms in the X/Z plane, with Y as vertical and floor at zero.
 * The construction owns its geometry/material; callers own mounting and unmounting the group.
 * Zones remain authored 2D-only; this constructor does not silently infer 3D zone topology.
 */
export function buildFloorplanGeometry(source: FloorplanGeometrySnapshot): AuthoredFloorplanGeometry {
  const snapshot = snapshotWalls(source);
  const group = new Group();
  group.name = "authored-floorplan";
  const walls = new Map<number, WallMesh>();
  const geometry = snapshot.walls.length ? new BoxGeometry() : null;
  const material = geometry ? new MeshStandardMaterial({ color: 0x8398a1, roughness: 0.85 }) : null;
  if (geometry && material) for (const { id, start, end } of snapshot.walls) {
    const [x0, z0] = start, [x1, z1] = end;
    const dx = x1 - x0, dz = z1 - z0;
    const wall = new Mesh(geometry, material);
    wall.name = "floorplan-wall:" + id;
    wall.position.set((x0 + x1) / 20, WALL_HEIGHT_M / 2, (z0 + z1) / 20);
    wall.rotation.y = -Math.atan2(dz, dx);
    wall.scale.set(Math.hypot(dx, dz) / 10, WALL_HEIGHT_M, WALL_THICKNESS_M);
    group.add(wall);
    walls.set(id, wall);
  }
  let disposed = false;
  return {
    snapshot, group, walls,
    dispose() {
      if (disposed) return;
      disposed = true;
      group.clear();
      walls.clear();
      geometry?.dispose();
      material?.dispose();
    },
  };
}
