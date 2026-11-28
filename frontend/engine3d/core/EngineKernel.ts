import { Color, DirectionalLight, GridHelper, HemisphereLight, PerspectiveCamera, Scene, Vector3, WebGLRenderer } from "three";
import { fitDeviceCamera, overviewFar } from "./cameraFocus.ts";
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
  const camera = new PerspectiveCamera(48, 1, 0.1, overviewFar);
  const origin = new Vector3();
  let focusedId: string | undefined;
  const objects = buildSceneObjects(sceneDocument);
  const grid = new GridHelper(32, 32, 0x668d86, 0xbaccc8);
  const key = new DirectionalLight(0xffffff, 2);
  key.position.set(6, 15, 8);
  scene.add(objects.group, grid, key, new HemisphereLight(0xf0f9ff, 0xa1b7a7, 2));
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
      const device = focusedId === undefined ? undefined : objects.devices.get(focusedId);
      if (device) fitDeviceCamera(camera, device);
      else {
        camera.far = overviewFar;
        camera.position.set(16, 14, 19);
        camera.lookAt(origin);
        camera.updateProjectionMatrix();
      }
      renderer.render(scene, camera);
    });
  }
  function focus(id?: string) {
    if (disposed) return false;
    if (id !== undefined && !objects.devices.has(id)) return false;
    focusedId = id;
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
      objects.dispose();
      grid.dispose();
      scene.clear();
      renderer.dispose();
    },
  };
}
