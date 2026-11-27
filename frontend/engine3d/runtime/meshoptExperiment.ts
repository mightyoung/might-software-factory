import { createMeshoptWorkerClient, type MeshoptPort } from "../core/meshoptWorkerClient.ts";
import { MESHOPT_INPUT } from "../core/meshoptProtocol.ts";

export function mountMeshoptExperiment(display: (name: string, bytes: ArrayBuffer) => void, invalidateDefault: () => void,
  closePreview: () => void) {
  const input = document.querySelector<HTMLInputElement>("#meshopt-file")!;
  const status = document.getElementById("meshopt-status")!;
  const close = document.querySelector<HTMLButtonElement>("#meshopt-close")!;
  const client = createMeshoptWorkerClient(() => {
    const worker = new Worker(new URL("../core/meshopt.worker.ts", import.meta.url), { type: "module" });
    const port: MeshoptPort = {
      onmessage: null, onerror: null, onmessageerror: null,
      postMessage: (message, transfer) => worker.postMessage(message, transfer),
      terminate: () => worker.terminate(),
    };
    worker.onmessage = (event) => port.onmessage?.(event);
    worker.onerror = (event) => { event.preventDefault(); port.onerror?.(); };
    worker.onmessageerror = () => port.onmessageerror?.();
    return port;
  });
  let revision = 0;
  let showing = false;
  function cancel() {
    revision++;
    client.cancel();
    status.textContent = "";
    close.hidden = !showing;
  }
  function reset() {
    showing = false;
    cancel();
  }
  close.addEventListener("click", () => {
    const wasShowing = showing;
    reset();
    if (wasShowing) closePreview();
    input.focus();
  });
  input.addEventListener("change", async () => {
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    invalidateDefault();
    cancel();
    const mine = revision;
    close.hidden = false;
    status.textContent = "正在本地解码实验模型…";
    try {
      if (!file.name.toLowerCase().endsWith(".glb") || file.size > MESHOPT_INPUT)
        throw new Error("请选择符合实验上限的 .glb 文件。");
      const bytes = await file.arrayBuffer();
      if (mine !== revision) return;
      const decoded = await client.run(bytes);
      if (mine !== revision || !decoded) return;
      display(file.name, decoded);
      showing = true;
      status.textContent = "实验模型已就绪。不会入库、保存或放映。";
    } catch (failure) {
      if (mine !== revision) return;
      status.textContent = failure instanceof Error ? failure.message : "无法读取实验模型。";
      close.hidden = !showing;
    }
  });
  return { cancel, reset };
}
