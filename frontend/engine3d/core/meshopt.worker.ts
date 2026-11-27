import { decodeMeshoptPositions } from "./meshoptDecode.ts";
import { isMeshoptBytes, isOwnShape, MESHOPT_INPUT, type MeshoptReply } from "./meshoptProtocol.ts";

const scope = self as unknown as {
  onmessage: ((event: MessageEvent<unknown>) => void) | null;
  postMessage(value: MeshoptReply, transfers?: Transferable[]): void;
};
scope.onmessage = async ({ data }) => {
  // A worker is single-use. Its parent terminates it after the result or cancellation.
  scope.onmessage = null;
  if (isOwnShape(data, ["bytes"]) && isMeshoptBytes(data.bytes, MESHOPT_INPUT)) {
    try {
      const bytes = await decodeMeshoptPositions(data.bytes);
      scope.postMessage({ ok: true, bytes }, [bytes]);
      return;
    } catch { /* Decoder errors or malformed descriptors never escape this boundary. */ }
  }
  scope.postMessage({ ok: false });
};
