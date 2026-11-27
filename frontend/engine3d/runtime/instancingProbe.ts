import { Color, OrthographicCamera, Scene, WebGLRenderer } from "three";
import { createInstancingFixture, MAX_PROBE_DEVICES, type ProbeMode } from "../core/instancingProbe.ts";

const canvas = document.querySelector<HTMLCanvasElement>("#probe-canvas")!;
const run = document.querySelector<HTMLButtonElement>("#run-probe")!;
const status = document.querySelector("#probe-status")!;
const scene = new Scene();
scene.background = new Color(0xe6eeec);
const camera = new OrthographicCamera(-40, 40, 28, -28, 0.1, 180);
camera.position.set(30, 56, 54);
camera.lookAt(0, 0, 0);
let renderer: WebGLRenderer | undefined;
let contextLost = false;
const report = (id: string, value: string) => { document.getElementById("probe-" + id)!.textContent = value; };

function compare(mode: ProbeMode, current: WebGLRenderer) {
  const fixture = createInstancingFixture(mode, MAX_PROBE_DEVICES);
  scene.add(fixture.group);
  try {
    current.render(scene, camera);
    const { calls, triangles } = current.info.render;
    return { calls, triangles, cleanup() {
      fixture.dispose();
      // Re-render an empty scene with the same live renderer so its cache
      // cannot mask tracked geometry still retained by this fixture.
      current.render(scene, camera);
      return { trackedGeometries: current.info.memory.geometries, released: fixture.releaseCounts() };
    } };
  } catch (failure) {
    fixture.dispose();
    throw failure;
  }
}

run.addEventListener("click", () => {
  for (const id of ["draws-individual", "draws-instanced", "triangles", "geometry", "disposals"]) report(id, "");
  if (contextLost) return;
  try {
    renderer ??= new WebGLRenderer({ canvas, antialias: false, alpha: false });
    const individual = compare("individual", renderer);
    const afterIndividual = individual.cleanup();
    const instanced = compare("instanced", renderer);
    const afterInstanced = instanced.cleanup();
    report("draws-individual", String(individual.calls));
    report("draws-instanced", String(instanced.calls));
    report("triangles", `${individual.triangles} / ${instanced.triangles}`);
    report("geometry", `${afterIndividual.trackedGeometries} / ${afterInstanced.trackedGeometries}`);
    report("disposals", `${afterIndividual.released.geometry + afterInstanced.released.geometry} / ${afterIndividual.released.material + afterInstanced.released.material} / ${afterInstanced.released.instance}`);
    status.textContent = "对比完成。";
  } catch {
    status.textContent = "此浏览器无法运行 WebGL 探针。";
  }
});
canvas.addEventListener("webglcontextlost", (event) => {
  event.preventDefault();
  contextLost = true;
  run.disabled = true;
  status.textContent = "WebGL 上下文已丢失。";
});
canvas.addEventListener("webglcontextrestored", () => {
  contextLost = false;
  run.disabled = false;
  status.textContent = "已恢复，可重新对比。";
});
window.addEventListener("pagehide", (event) => {
  if (!event.persisted) { renderer?.dispose(); renderer = undefined; }
});
