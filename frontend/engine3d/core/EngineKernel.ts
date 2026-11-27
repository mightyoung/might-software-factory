import { Color, DirectionalLight, GridHelper, HemisphereLight, Mesh, MeshStandardMaterial, PerspectiveCamera, Scene, Vector3, WebGLRenderer } from "three";
import { PreviewSlot } from "./PreviewSlot.ts";
import type { StaticGlb } from "./glbIntake.ts";
import { buildSceneObjects } from "./sceneObjects.ts";
import type { DeviceState, SceneDocument } from "../schema/scene.ts";

export type RenderStatus = "ready" | "lost" | "unavailable";

export function mountEngine(
  canvas: HTMLCanvasElement, sceneDocument: SceneDocument,
  onStatus: (status: RenderStatus) => void,
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
  let objects = buildSceneObjects(sceneDocument);
  const grid = new GridHelper(32, 32, 0x668d86, 0xbaccc8);
  const key = new DirectionalLight(0xffffff, 2);
  key.position.set(6, 15, 8);
  scene.add(objects.group, grid, key, new HemisphereLight(0xf0f9ff, 0xa1b7a7, 2));
  const preview = new PreviewSlot();
  const previewMaterial = new MeshStandardMaterial({ color: 0x247c72, roughness: 0.56 });
  let previewMesh: Mesh | null = null;
  function clearModel() {
    if (previewMesh) scene.remove(previewMesh);
    previewMesh = null;
    preview.clear();
    objects.group.visible = true;
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
      if (!width || !height) return;
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      if (renderer.getPixelRatio() !== ratio) renderer.setPixelRatio(ratio);
      if (canvas.width !== Math.floor(width * ratio) || canvas.height !== Math.floor(height * ratio)) {
        renderer.setSize(width, height, false);
      }
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      renderer.render(scene, camera);
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
  const lose = (event: Event) => { event.preventDefault(); lost = true; stop(); onStatus("lost"); };
  const restore = () => { lost = false; render(); onStatus("ready"); };
  const visibility = () => { if (doc.hidden) stop(); else render(); };
  const resize = new ResizeObserver(render);
  resize.observe(canvas);
  doc.addEventListener("visibilitychange", visibility);
  canvas.addEventListener("webglcontextlost", lose);
  canvas.addEventListener("webglcontextrestored", restore);
  focus();
  onStatus("ready");

  return {
    focus,
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
      objects = buildSceneObjects(next);
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
      objects.dispose();
      grid.dispose();
      scene.clear();
      renderer.dispose();
    },
  };
}
