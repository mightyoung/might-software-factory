import { openStaticGlb } from "./glbIntake.ts";
import { decodeMeshoptPositions } from "./meshoptDecode.ts";

export async function openMeshoptPreview(data: ArrayBuffer) {
  return openStaticGlb(await decodeMeshoptPositions(data));
}
