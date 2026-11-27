import { paintPreviewThumbnail } from "./thumbnail.ts";
import { createVisualReferenceSlot, type ReferencePoint } from "./visualReference.ts";
import type { SourceUnit } from "./modelUnits.ts";
import { Color, DirectionalLight, GridHelper, HemisphereLight, Mesh, MeshStandardMaterial, PerspectiveCamera, Scene, Vector3, WebGLRenderer } from "three";
import { buildFloorplanGeometry, type FloorplanGeometrySnapshot } from "./floorplanGeometry.ts";
import { PreviewSlot } from "./PreviewSlot.ts";
import type { StaticGlb } from "./glbIntake.ts";
import { buildSceneObjects, type SceneBuildMode } from "./sceneObjects.ts";
import { resizeFaces, type ResizeFace } from "./equipmentResize.ts";
import type { DeviceState, ParametricEquipment, SceneDocument } from "../schema/scene.ts";

export type RenderStatus = "ready" | "lost" | "unavailable";

export function mountEngine(
  canvas: HTMLCanvasElement, sceneDocument: SceneDocument,
  onStatus: (status: RenderStatus) => void,
  onFrame?: () => void,
  options: { sceneMode?: SceneBuildMode; allowVisualReference?: boolean } = {},
) {
  let renderer: WebGLRenderer;
  try {
    renderer = new WebGLRenderer({ canvas, antialias: true, alpha: false });
  } catch {
    onStatus("unavailable");
    return null;
  }
  const scene = new Scene();
  scene.background = new Color(0xe6eeec);
  const camera = new PerspectiveCamera(48, 1, 0.1, 3000);
  const target = new Vector3();
  let objects = buildSceneObjects(sceneDocument, options.sceneMode);
  const grid = new GridHelper(32, 32, 0x668d86, 0xbaccc8);
  const key = new DirectionalLight(0xffffff, 2);
  key.position.set(6, 15, 8);
  scene.add(objects.group, grid, key, new HemisphereLight(0xf0f9ff, 0xa1b7a7, 2));
  const preview = new PreviewSlot();
  const previewMaterial = new MeshStandardMaterial({ color: 0x247c72, roughness: 0.56 });
  let floorplan: ReturnType<typeof buildFloorplanGeometry> | null = null;
  let previewMesh: Mesh | null = null;
  let visualReference: ReturnType<typeof createVisualReferenceSlot> | null = null;
  let thumbnail: { target: HTMLCanvasElement; complete: (captured: boolean) => void } | undefined;
  function cancelThumbnail() {
    const pending = thumbnail;
    thumbnail = undefined;
    pending?.complete(false);
  }
  function clearModel() {
    cancelThumbnail();
    if (previewMesh) scene.remove(previewMesh);
    previewMesh = null;
    preview.clear();
    objects.group.visible = true;
    if (floorplan) floorplan.group.visible = true;
    if (visualReference) visualReference.group.visible = true;
  }
  let frame = 0;
  let disposed = false;
  let lost = false;
  const doc = canvas.ownerDocument;
  function stop() {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
  }
  function render() {
    if (disposed || lost || doc.hidden || frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      const { clientWidth: width, clientHeight: height } = canvas;
      if (!width || !height) { cancelThumbnail(); return; }
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      if (renderer.getPixelRatio() !== ratio) renderer.setPixelRatio(ratio);
      if (canvas.width !== Math.floor(width * ratio) || canvas.height !== Math.floor(height * ratio)) {
        renderer.setSize(width, height, false);
      }
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      renderer.render(scene, camera);
      if (thumbnail) {
        const { target, complete } = thumbnail;
        thumbnail = undefined;
        let captured = false;
        // Copy while this frame is still readable, without preserveDrawingBuffer or a second WebGL context.
        try { captured = paintPreviewThumbnail(canvas, target); }
        catch { /* A thumbnail failure must not stop the primary renderer. */ }
        complete(captured);
      }
      onFrame?.();
    });
  }
  function focus(id?: string) {
    if (disposed) return false;
    const object = id ? objects.devices.get(id) : undefined;
    if (id && !object) return false;
    target.set(0, 0, 0);
    if (object) target.copy(object.position);
    camera.position.copy(target).add(new Vector3(...(object ? [6, 5, 7] : [16, 14, 19])));
    camera.lookAt(target);
    render();
    return true;
  }
  const lose = (event: Event) => { event.preventDefault(); lost = true; stop(); cancelThumbnail(); onStatus("lost"); };
  const restore = () => { lost = false; render(); onStatus("ready"); };
  const visibility = () => { if (doc.hidden) { stop(); cancelThumbnail(); } else render(); };
  const resize = new ResizeObserver(render);
  resize.observe(canvas);
  doc.addEventListener("visibilitychange", visibility);
  canvas.addEventListener("webglcontextlost", lose);
  canvas.addEventListener("webglcontextrestored", restore);
  focus();
  onStatus("ready");

  return {
    focus,
    replaceFloorplan(snapshot: FloorplanGeometrySnapshot) {
      if (disposed) return false;
      const next = buildFloorplanGeometry(snapshot);
      if (floorplan) { scene.remove(floorplan.group); floorplan.dispose(); }
      floorplan = next;
      floorplan.group.visible = !preview.current;
      scene.add(floorplan.group);
      render();
      return true;
    },
    projectedHandles(id: string, { length, width, height }: ParametricEquipment["dimensions"]) {
      const object = objects.devices.get(id);
      if (disposed || lost || preview.current || !object) return [];
      const w = canvas.clientWidth, h = canvas.clientHeight;
      if (!w || !h) return [];
      object.updateWorldMatrix(true, false);
      function project(point: Vector3): [number, number, number] {
        const world = object!.localToWorld(point).project(camera);
        return [(world.x + 1) * w / 2, (1 - world.y) * h / 2, world.z];
      }
      const center: Record<ResizeFace, [number, number, number]> = {
        "+X": [length / 2, height / 2, 0], "-X": [-length / 2, height / 2, 0],
        "+Z": [0, height / 2, width / 2], "-Z": [0, height / 2, -width / 2],
        "+Y": [0, height, 0],
      };
      return resizeFaces.flatMap((face) => {
        const point = new Vector3(...center[face]);
        const [x, y, depth] = project(point.clone());
        const axis = face[1] === "X" ? "x" : face[1] === "Y" ? "y" : "z";
        point[axis] += face[0] === "+" ? 1 : -1;
        const [endX, endY] = project(point);
        if (![x, y, depth, endX, endY].every(Number.isFinite) || Math.abs(depth) > 1 ||
            x < 8 || x > w - 8 || y < 8 || y > h - 8 || Math.hypot(endX - x, endY - y) < 6) return [];
        return [{ deviceId: id, face, x, y, axis: [endX - x, endY - y] as [number, number] }];
      });
    },
    placeVisualReference(model: StaticGlb, unit: SourceUnit, point: ReferencePoint) {
      if (disposed || lost || !options.allowVisualReference) return false;
      if (visualReference) visualReference.replace(model, unit, point);
      else {
        const next = createVisualReferenceSlot();
        try { next.replace(model, unit, point); }
        catch (failure) { next.dispose(); throw failure; }
        visualReference = next;
        scene.add(next.group);
      }
      visualReference.group.visible = !preview.current;
      render();
      return true;
    },
    removeVisualReference() {
      if (!visualReference) return;
      visualReference.dispose();
      visualReference = null;
      render();
    },
    requestPreviewThumbnail(target: HTMLCanvasElement, complete: (captured: boolean) => void) {
      if (disposed || lost || doc.hidden || !preview.current || thumbnail || !canvas.clientWidth || !canvas.clientHeight) return false;
      thumbnail = { target, complete };
      render();
      return true;
    },
    showModel(model: StaticGlb) {
      if (disposed) return false;
      if (model === preview.current) return true;
      const bounds = model.geometry.boundingBox!;
      const center = bounds.getCenter(new Vector3());
      const scale = 6 / Math.max(...model.metrics.dimensions);
      const mesh = new Mesh(model.geometry, previewMaterial);
      mesh.scale.setScalar(scale);
      mesh.position.set(-center.x * scale, -bounds.min.y * scale, -center.z * scale);
      clearModel();
      preview.replace(model);
      previewMesh = mesh;
      objects.group.visible = false;
      if (floorplan) floorplan.group.visible = false;
      if (visualReference) visualReference.group.visible = false;
      scene.add(mesh);
      target.set(0, model.metrics.dimensions[1] * scale / 2, 0);
      camera.position.copy(target).add(new Vector3(8, 6, 9));
      camera.lookAt(target);
      render();
      return true;
    },
    hideModel(id?: string) {
      if (disposed) return;
      clearModel();
      focus(id);
    },
    replaceScene(next: SceneDocument) {
      if (disposed) return false;
      const previous = objects;
      objects = buildSceneObjects(next, options.sceneMode);
      scene.remove(previous.group);
      previous.dispose();
      objects.group.visible = !preview.current;
      scene.add(objects.group);
      render();
      return true;
    },
    setState(id: string, state: DeviceState) {
      const changed = objects.setState(id, state);
      if (changed) render();
      return changed;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      stop();
      resize.disconnect();
      doc.removeEventListener("visibilitychange", visibility);
      canvas.removeEventListener("webglcontextlost", lose);
      canvas.removeEventListener("webglcontextrestored", restore);
      clearModel();
      previewMaterial.dispose();
      visualReference?.dispose();
      visualReference = null;
      floorplan?.dispose();
      floorplan = null;
      objects.dispose();
      grid.dispose();
      scene.clear();
      renderer.dispose();
    },
  };
}
