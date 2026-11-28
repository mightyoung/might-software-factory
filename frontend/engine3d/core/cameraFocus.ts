import { Box3, MathUtils, Object3D, PerspectiveCamera, Sphere, Vector3 } from "three";

const approach = new Vector3(6, 5, 7);
const baseline = approach.length();
approach.normalize();
export const overviewFar = 3000;

/** Fit a conservative sphere of the device's world bounds without changing the lens. */
export function fitDeviceCamera(camera: PerspectiveCamera, device: Object3D) {
  const sphere = new Box3().setFromObject(device).getBoundingSphere(new Sphere());
  const vertical = MathUtils.degToRad(camera.getEffectiveFOV()) / 2;
  const horizontal = Math.atan(Math.tan(vertical) * camera.aspect);
  const paddedRadius = sphere.radius * 1.12;
  const distance = Math.max(baseline, paddedRadius / Math.sin(Math.min(vertical, horizontal)), paddedRadius + camera.near);
  camera.position.copy(sphere.center).addScaledVector(approach, distance);
  camera.lookAt(sphere.center);
  camera.far = Math.max(overviewFar, distance + paddedRadius + camera.near);
  camera.updateProjectionMatrix();
}
