export type Point = readonly [number, number];
export type GridStep = "0.1" | "0.5" | "1";
export type FloorplanMode = "wall" | "zone";
export type Wall = { id: number; start: Point; end: Point };
export type Zone = { id: number; points: readonly Point[]; area: number };
type CommittedShape = { kind: "wall"; value: Wall } | { kind: "zone"; value: Zone };

const steps = { "0.1": 1, "0.5": 5, "1": 10 } as const; // decimetres
const same = (a: Point, b: Point) => a[0] === b[0] && a[1] === b[1];
const cross = (a: Point, b: Point, c: Point) =>
  (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
const within = (a: number, b: number, p: number) => p >= Math.min(a, b) && p <= Math.max(a, b);
function onSegment(a: Point, b: Point, p: Point) {
  return cross(a, b, p) === 0 && within(a[0], b[0], p[0]) && within(a[1], b[1], p[1]);
}
function intersects(a: Point, b: Point, c: Point, d: Point) {
  const x = cross(a, b, c), y = cross(a, b, d), z = cross(c, d, a), w = cross(c, d, b);
  return (x * y < 0 && z * w < 0) ||
    onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b);
}

export function polygonArea(points: readonly Point[]): number {
  let double = 0;
  for (let i = 0; i < points.length; i++) {
    const [x, z] = points[i], [nextX, nextZ] = points[(i + 1) % points.length];
    double += x * nextZ - nextX * z;
  }
  return Math.abs(double) / 200;
}

export function validatePolygon(points: readonly Point[]) {
  const n = points.length;
  if (n < 3) throw new Error("区域至少需要三个点。");
  if (new Set(points.map((point) => point.join(","))).size !== n) throw new Error("区域不能包含重复顶点。");
  for (let i = 0; i < n; i++) {
    const a = points[i], b = points[(i + 1) % n], c = points[(i + 2) % n];
    if (onSegment(a, b, c) || onSegment(b, c, a)) throw new Error("相邻边不能折返或重叠。");
    for (let j = i + 2; j < n; j++) {
      if (i === 0 && j === n - 1) continue;
      if (intersects(a, b, points[j], points[(j + 1) % n])) throw new Error("区域边不能交叉或相互接触。");
    }
  }
  const area = polygonArea(points);
  if (area === 0) throw new Error("区域面积必须大于零。");
  return area;
}

export class FloorplanDraft {
  mode: FloorplanMode = "wall";
  grid: GridStep = "0.5";
  readonly walls: Wall[] = [];
  readonly zones: Zone[] = [];
  private vertices: Point[] = [];
  private nextId = 1;
  private readonly committed: CommittedShape[] = [];
  private readonly undone: CommittedShape[] = [];
  private get editingZone() { return this.mode === "zone" && this.vertices.length > 0; }
  get canUndo() { return !this.editingZone && this.committed.length > 0; }
  get canRedo() { return !this.editingZone && this.undone.length > 0; }
  private record(shape: CommittedShape) {
    this.undone.length = 0;
    this.committed.push(structuredClone(shape));
    if (this.committed.length > 20) this.committed.shift();
  }
  private allocateId() {
    if (this.nextId > 10_000) throw new Error("草稿图形编号已满。");
    return this.nextId++;
  }
  undo() {
    if (!this.canUndo) return false;
    const shape = this.committed.at(-1)!;
    const shapes = shape.kind === "wall" ? this.walls : this.zones;
    if (shapes.at(-1)?.id !== shape.value.id) return false;
    shapes.pop();
    this.committed.pop();
    this.undone.push(shape);
    this.cancel();
    return true;
  }
  redo() {
    if (!this.canRedo) return false;
    const shape = this.undone.pop()!;
    if (shape.kind === "wall") this.walls.push(structuredClone(shape.value));
    else this.zones.push(structuredClone(shape.value));
    this.committed.push(shape);
    this.cancel();
    return true;
  }
  get points(): readonly Point[] { return this.vertices; }
  setMode(mode: FloorplanMode) { if (mode !== this.mode) { this.mode = mode; this.cancel(); } }
  setGrid(grid: GridStep) { this.grid = grid; }
  cancel() { this.vertices = []; }
  snapshot() {
    return { walls: structuredClone(this.walls), zones: structuredClone(this.zones.map(({ id, points }) => ({ id, points }))) };
  }
  restore(plan: { walls: Wall[]; zones: Zone[] }) {
    this.walls.splice(0, this.walls.length, ...structuredClone(plan.walls));
    this.zones.splice(0, this.zones.length, ...structuredClone(plan.zones));
    this.nextId = 1 + Math.max(0, ...this.walls.map((shape) => shape.id), ...this.zones.map((shape) => shape.id));
    this.committed.length = this.undone.length = 0;
    this.cancel();
  }
  preview(rawMetres: Point, ortho = false): Point {
    if (rawMetres.some((value) => !Number.isFinite(value) || Math.abs(value) > 10))
      throw new Error("坐标必须在预览范围 ±10 米内。");
    const step = steps[this.grid];
    const snap = (n: number) => Math.round(n * 10 / step) * step || 0;
    const x = snap(rawMetres[0]), z = snap(rawMetres[1]);
    const anchor = this.vertices.at(-1);
    if (!ortho || !anchor) return [x, z];
    return Math.abs(x - anchor[0]) >= Math.abs(z - anchor[1]) ? [x, anchor[1]] : [anchor[0], z];
  }
  add(rawMetres: Point, ortho = false) {
    const point = this.preview(rawMetres, ortho);
    const anchor = this.vertices.at(-1);
    if (this.mode === "wall" && anchor) {
      if (same(anchor, point)) throw new Error("墙段不能为零长。");
      if (this.walls.length >= 100) throw new Error("最多画 100 条墙段。");
      if (this.walls.some(({ start, end }) => same(start, anchor) && same(end, point) || same(start, point) && same(end, anchor)))
        throw new Error("这条墙段已存在。");
      const wall = { id: this.allocateId(), start: anchor, end: point };
      this.walls.push(wall);
      this.record({ kind: "wall", value: wall });
      this.vertices = [point];
    } else {
      if (this.vertices.length >= 60) throw new Error("每个区域最多 60 个顶点。");
      this.vertices.push(point);
    }
    return point;
  }
  finishZone(): Zone {
    if (this.mode !== "zone" || this.zones.length >= 20) throw new Error("请选择画区域，最多保留 20 个。");
    const area = validatePolygon(this.vertices);
    const zone = { id: this.allocateId(), points: [...this.vertices], area };
    this.zones.push(zone);
    this.record({ kind: "zone", value: zone });
    this.cancel();
    return zone;
  }
}
