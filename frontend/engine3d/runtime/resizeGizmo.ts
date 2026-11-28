import { resizeFaces, type ResizeFace } from "../core/equipmentResize.ts";
import { projectedResizeDelta } from "../core/resizeProjection.ts";

type Handle = { deviceId: string; face: ResizeFace; x: number; y: number; axis: [number, number] };
export function mountResizeGizmo(root: HTMLElement, viewport: HTMLCanvasElement, getHandles: () => Handle[], commit: (metres: number, face: ResizeFace, deviceId: string) => void) {
  let positions = new Map<ResizeFace, Handle>();
  let drag: { id: number; deviceId: string; face: ResizeFace; handle: Handle; viewport: DOMRect; from: [number, number]; node: HTMLElement } | null = null;
  function cancel() {
    const previous = drag;
    drag = null;
    if (previous?.node.hasPointerCapture(previous.id)) previous.node.releasePointerCapture(previous.id);
    if (previous) previous.node.style.removeProperty("transform");
  }
  function isCurrent() {
    if (!drag) return false;
    const rect = viewport.getBoundingClientRect();
    const original = drag.viewport;
    if (rect.x !== original.x || rect.y !== original.y || rect.width !== original.width || rect.height !== original.height) return false;
    const captured = drag.handle;
    const current = positions.get(captured.face);
    return current?.deviceId === captured.deviceId &&
      Math.max(Math.abs(current.x - captured.x), Math.abs(current.y - captured.y),
        Math.abs(current.axis[0] - captured.axis[0]), Math.abs(current.axis[1] - captured.axis[1])) < 0.01;
  }
  const items = resizeFaces.map((face) => {
    const node = document.createElement("span");
    node.className = "resize-handle";
    node.dataset.face = face;
    node.title = face + " 方向调整尺寸";
    node.hidden = true;
    node.addEventListener("pointerdown", (event) => {
      const handle = positions.get(face);
      if (!handle || event.button !== 0 || drag) return;
      event.preventDefault();
      drag = { id: event.pointerId, deviceId: handle.deviceId, face, handle, viewport: viewport.getBoundingClientRect(), from: [event.clientX, event.clientY], node };
      node.setPointerCapture(event.pointerId);
    });
    node.addEventListener("pointermove", (event) => {
      if (drag?.id !== event.pointerId || drag.node !== node) return;
      if (!isCurrent()) { cancel(); return; }
      const delta = projectedResizeDelta(drag.handle.axis, [event.clientX - drag.from[0], event.clientY - drag.from[1]]);
      if (delta !== null) node.style.transform = `translate(calc(-50% + ${drag.handle.axis[0] * delta}px), calc(-50% + ${drag.handle.axis[1] * delta}px))`;
    });
    node.addEventListener("pointerup", (event) => {
      if (drag?.id !== event.pointerId || drag.node !== node) return;
      if (!isCurrent()) { cancel(); return; }
      const delta = projectedResizeDelta(drag.handle.axis, [event.clientX - drag.from[0], event.clientY - drag.from[1]]);
      const deviceId = drag.deviceId;
      const sameDevice = positions.get(face)?.deviceId === deviceId;
      cancel();
      if (sameDevice && delta !== null && Math.abs(delta) >= 0.025) commit(delta, face, deviceId);
    });
    node.addEventListener("pointercancel", cancel);
    node.addEventListener("lostpointercapture", () => { if (drag?.node === node) cancel(); });
    root.append(node);
    return { face, node };
  });
  window.addEventListener("blur", cancel);
  window.addEventListener("keydown", (event) => { if (event.key === "Escape") cancel(); });
  function update() {
    positions = new Map(getHandles().map((handle) => [handle.face, handle]));
    if (drag && !isCurrent()) cancel();
    for (const { face, node } of items) {
      const handle = positions.get(face);
      node.hidden = !handle;
      if (!handle) continue;
      node.style.left = `${handle.x}px`;
      node.style.top = `${handle.y}px`;
      // Only an unobtrusive projected vector, useful in deterministic browser checks.
      node.dataset.axis = handle.axis.join(",");
    }
  }
  return { update, cancel };
}
