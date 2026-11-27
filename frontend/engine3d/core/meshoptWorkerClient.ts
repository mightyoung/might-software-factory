import { isMeshoptBytes, isOwnShape, MESHOPT_INPUT, MESHOPT_OUTPUT, type MeshoptRequest } from "./meshoptProtocol.ts";

export type MeshoptPort = {
  onmessage: ((event: { data: unknown }) => void) | null;
  onerror: (() => void) | null;
  onmessageerror: (() => void) | null;
  postMessage(message: MeshoptRequest, transfer: Transferable[]): void;
  terminate(): void;
};

export function createMeshoptWorkerClient(create: () => MeshoptPort) {
  let active: (() => void) | undefined;
  function cancel() { active?.(); }
  async function run(bytes: ArrayBuffer) {
    cancel();
    if (!isMeshoptBytes(bytes, MESHOPT_INPUT)) throw new Error("GLB：实验模型文件大小超出范围。");
    const port = create();
    return new Promise<ArrayBuffer | null>((resolve, reject) => {
      const stop = () => { if (settle()) resolve(null); };
      const timer = setTimeout(() => fail("Meshopt 解码超时。"), 5000);
      active = stop;
      function settle() {
        if (active !== stop) return false;
        active = undefined;
        clearTimeout(timer);
        port.onmessage = port.onerror = port.onmessageerror = null;
        port.terminate();
        return true;
      }
      function fail(message = "GLB：实验 Meshopt 文件无法解码。") {
        if (settle()) reject(new Error(message));
      }
      port.onmessage = ({ data }) => {
        if (isOwnShape(data, ["ok", "bytes"]) && data.ok === true && isMeshoptBytes(data.bytes, MESHOPT_OUTPUT)) {
          if (settle()) resolve(data.bytes);
        } else if (isOwnShape(data, ["ok"]) && data.ok === false) fail();
        else fail("GLB：实验解码结果不符合契约。");
      };
      port.onerror = port.onmessageerror = () => fail();
      try { port.postMessage({ bytes }, [bytes]); }
      catch { fail(); }
    });
  }
  return { run, cancel };
}
