import assert from "node:assert/strict";
import { test } from "node:test";
import { Box3, PerspectiveCamera, Sphere, Vector3 } from "three";
import { fitDeviceCamera } from "./cameraFocus.ts";
import { buildSceneObjects } from "./sceneObjects.ts";
import { SceneDocumentSchema } from "../schema/scene.ts";

for (const primitive of ["box", "cylinder"] as const) test(`fits maximum ${primitive} bounds in tall and wide frusta`, () => {
  const doc = SceneDocumentSchema.parse({ schemaVersion: 1, screenId: "synthetic", devices: [
    { id: "max", primitive, label: "Fixture", state: "normal", position: [950, -100, -950], size: [100, 100, 100] },
  ] });
  const scene = buildSceneObjects(doc);
  try {
    const device = scene.devices.get("max")!;
    const bounds = new Box3().setFromObject(device);
    const center = bounds.getCenter(new Vector3());
    const camera = new PerspectiveCamera(48, 1, 0.1, 3000);
    for (const aspect of [4, 0.2, 0.01, 4]) {
      camera.aspect = aspect;
      fitDeviceCamera(camera, device);
      camera.updateMatrixWorld();
      assert.equal(camera.fov, 48);
      assert.ok(camera.position.distanceTo(center) > bounds.getBoundingSphere(new Sphere()).radius);
      for (const x of [bounds.min.x, bounds.max.x])
        for (const y of [bounds.min.y, bounds.max.y])
          for (const z of [bounds.min.z, bounds.max.z]) {
            const point = new Vector3(x, y, z).project(camera);
            assert.ok(Math.abs(point.x) < 1 && Math.abs(point.y) < 1 && Math.abs(point.z) < 1);
          }
    }
    assert.equal(camera.far, 3000);
  } finally { scene.dispose(); }
});
