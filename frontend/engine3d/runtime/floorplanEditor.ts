import { FloorplanDraft, polygonArea, type FloorplanMode, type GridStep, type Point } from "../core/floorplan.ts";

const svgNS = "http://www.w3.org/2000/svg";
export function mountFloorplanEditor() {
  const draft = new FloorplanDraft();
  const map = document.querySelector<SVGSVGElement>("#floorplan-map")!;
  const features = document.querySelector<SVGGElement>("#plan-features")!;
  const mode = document.querySelector<HTMLSelectElement>("#plan-mode")!;
  const grid = document.querySelector<HTMLSelectElement>("#plan-grid")!;
  const ortho = document.querySelector<HTMLInputElement>("#plan-ortho")!;
  const close = document.querySelector<HTMLButtonElement>("#plan-close")!;
  const summary = document.querySelector<HTMLParagraphElement>("#plan-summary")!;
  const error = document.querySelector<HTMLParagraphElement>("#plan-error")!;
  const zones = document.querySelector<HTMLUListElement>("#plan-zones")!;
  const x = document.querySelector<HTMLInputElement>("#plan-x")!;
  const z = document.querySelector<HTMLInputElement>("#plan-z")!;
  let cursor: Point | null = null;

  function svg(tag: "polyline" | "polygon" | "circle", className: string, attributes: Record<string, string>) {
    const item = document.createElementNS(svgNS, tag);
    item.setAttribute("class", className);
    for (const [key, value] of Object.entries(attributes)) item.setAttribute(key, value);
    features.append(item);
  }
  const coordinates = (points: readonly Point[]) => points.map((point) => point.join(",")).join(" ");
  function renderView() {
    features.replaceChildren();
    for (const wall of draft.walls) svg("polyline", "plan-wall", { points: coordinates([wall.start, wall.end]) });
    for (const zone of draft.zones) svg("polygon", "plan-zone", { points: coordinates(zone.points) });
    const points = cursor && draft.points.length ? [...draft.points, cursor] : draft.points;
    if (points.length >= 2) svg("polyline", "plan-draft", { points: coordinates(points) });
    for (const point of draft.points) svg("circle", "plan-dot", { cx: String(point[0]), cy: String(point[1]), r: "1.3" });
  }
  function render() {
    renderView();
    close.disabled = draft.mode !== "zone" || draft.points.length < 3;
    const area = draft.mode === "zone" && draft.points.length >= 3 ? ` 当前草稿约 ${polygonArea(draft.points)} 平方米。` : "";
    summary.textContent = `墙段 ${draft.walls.length} 条，区域 ${draft.zones.length} 个。 草稿 ${draft.points.length} 个点。${area}`;
    zones.replaceChildren(...draft.zones.map((zone) => {
      const item = document.createElement("li");
      item.textContent = `区域 ${zone.id}：${zone.area} 平方米`;
      return item;
    }));
  }
  function apply(action: () => void) {
    try { action(); error.hidden = true; error.textContent = ""; }
    catch (cause) { error.textContent = cause instanceof Error ? cause.message : "绘制失败。"; error.hidden = false; }
    cursor = null;
    render();
  }
  function pointer(event: MouseEvent): Point | null {
    const matrix = map.getScreenCTM();
    if (!matrix) return null;
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
    return Math.abs(point.x) <= 100 && Math.abs(point.y) <= 100 ? [point.x / 10, point.y / 10] : null;
  }
  map.addEventListener("pointermove", (event) => {
    const point = pointer(event);
    cursor = point ? draft.preview(point, ortho.checked || event.shiftKey) : null;
    renderView();
  });
  map.addEventListener("pointerleave", () => { cursor = null; renderView(); });
  map.addEventListener("click", (event) => {
    const point = pointer(event);
    if (point) apply(() => { draft.add(point, ortho.checked || event.shiftKey); });
  });
  document.querySelector("#plan-coordinates")!.addEventListener("submit", (event) => {
    event.preventDefault();
    apply(() => { draft.add([x.valueAsNumber, z.valueAsNumber], ortho.checked); });
  });
  mode.addEventListener("change", () => apply(() => draft.setMode(mode.value as FloorplanMode)));
  grid.addEventListener("change", () => apply(() => {
    draft.setGrid(grid.value as GridStep);
    const size = String(Number(grid.value) * 10);
    const pattern = document.querySelector("#plan-pattern")!;
    pattern.setAttribute("width", size);
    pattern.setAttribute("height", size);
    document.querySelector("#plan-grid-lines")!.setAttribute("d", `M ${size} 0 H 0 V ${size}`);
  }));
  close.addEventListener("click", () => apply(() => { draft.finishZone(); }));
  document.querySelector("#plan-cancel")!.addEventListener("click", () => apply(() => draft.cancel()));
  render();
}
