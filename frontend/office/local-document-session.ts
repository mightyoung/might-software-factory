import { CommandType, type Univer } from "@univerjs/core";
import type { FUniver } from "@univerjs/core/facade";

interface LocalDocumentUnit<Snapshot> {
  getId(): string;
  save(): Snapshot;
}

interface LocalDocumentSession<Snapshot> {
  univer: Univer;
  univerAPI: FUniver;
  unit: LocalDocumentUnit<Snapshot>;
  name: "文档" | "工作簿";
  create(snapshot: Snapshot): LocalDocumentUnit<Snapshot>;
  validate(value: unknown): Snapshot;
  serialize(snapshot: Snapshot): string;
  filename(snapshot: Snapshot): string;
}

export function validateJsonStructure(value: unknown, name: string) {
  let nodes = 0;
  function inspect(node: unknown, depth: number) {
    if (++nodes > 500000 || depth > 40) throw new Error(`${name}结构过于复杂。`);
    if (!node || typeof node !== "object") return;
    for (const [key, child] of Object.entries(node)) {
      if (["__proto__", "constructor", "prototype"].includes(key)) throw new Error(`${name}包含无效属性。`);
      inspect(child, depth + 1);
    }
  }
  inspect(value, 0);
}

export function startLocalDocumentSession<Snapshot>(session: LocalDocumentSession<Snapshot>) {
  const { univer, univerAPI, name } = session;
  const channel = "might-office-v1";
  const status = document.querySelector<HTMLElement>("#status")!;
  const error = document.querySelector<HTMLElement>("#error")!;
  const fileInput = document.querySelector<HTMLInputElement>("#file")!;
  const importButton = document.querySelector<HTMLButtonElement>("#import")!;
  const exportButton = document.querySelector<HTMLButtonElement>("#export")!;
  let unit = session.unit;
  let baseline = JSON.stringify(unit.save());
  let dirty = false;
  let importedFile = "";
  let comparisonTimer = 0;
  let busy = false;
  let settled = univerAPI.getCurrentLifecycleStage() >= univerAPI.Enum.LifecycleStages.Steady;

  function post(type: string, extra: Record<string, unknown> = {}) {
    if (parent !== window) parent.postMessage({ channel, type, ...extra }, location.origin);
  }
  function update() {
    dirty = JSON.stringify(unit.save()) !== baseline;
    const contentState = dirty ? "有尚未导出的修改" : `${name}已就绪`;
    status.textContent = importedFile ? `已导入 ${importedFile}，${contentState}` : contentState;
    post("state", { dirty });
  }
  function ready() {
    baseline = JSON.stringify(unit.save());
    update();
    importButton.disabled = false;
    exportButton.disabled = false;
    post("ready");
  }
  function showError(message: string) {
    error.textContent = message;
    error.hidden = false;
  }
  const listener = univerAPI.addEvent(univerAPI.Event.CommandExecuted, event => {
    if (event.type !== CommandType.MUTATION || !settled || busy) return;
    dirty = true;
    // Internal recalculations may also mutate the snapshot. Compare their settled result.
    window.clearTimeout(comparisonTimer);
    comparisonTimer = window.setTimeout(update, 80);
  });
  const lifecycle = univerAPI.addEvent(univerAPI.Event.LifeCycleChanged, ({ stage }) => {
    if (!settled && stage === univerAPI.Enum.LifecycleStages.Steady) {
      settled = true;
      ready();
    }
  });
  importButton.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    const file = fileInput.files?.[0];
    fileInput.value = "";
    if (!file || busy) return;
    error.hidden = true;
    busy = true;
    importButton.disabled = true;
    exportButton.disabled = true;
    try {
      if (file.size > 5 * 1024 * 1024) throw new Error(`请选择不超过 5 MiB 的${name} JSON 文件。`);
      const snapshot = session.validate(JSON.parse(await file.text()));
      update();
      if (dirty && !window.confirm(`当前修改尚未导出。确认丢弃并导入另一份${name}？`)) return;
      const previous = unit.save();
      univerAPI.disposeUnit(unit.getId());
      try {
        unit = session.create(snapshot);
      } catch (failure) {
        unit = session.create(previous);
        throw failure;
      }
      baseline = JSON.stringify(unit.save());
      importedFile = file.name;
      update();
    } catch (failure) {
      showError(failure instanceof Error ? failure.message : `${name}导入失败，请核对文件。`);
    } finally {
      busy = false;
      importButton.disabled = false;
      exportButton.disabled = false;
    }
  });
  exportButton.addEventListener("click", () => {
    try {
      const snapshot = unit.save();
      const url = URL.createObjectURL(new Blob([session.serialize(snapshot)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = `${session.filename(snapshot).replace(/[\\/:*?"<>|]/g, "_") || name}.json`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      baseline = JSON.stringify(snapshot);
      update();
      status.textContent = "已发起本机文件下载，请确认文件已保存";
    } catch {
      showError(`${name}导出失败，请重试。`);
    }
  });
  window.addEventListener("message", event => {
    if (event.origin !== location.origin || event.source !== parent || event.data?.channel !== channel) return;
    if (event.data.type === "close-check") { update();post("close-state", { dirty }); }
    if (event.data.type === "theme" && (event.data.theme === "dark" || event.data.theme === "light")) {
      document.documentElement.dataset.theme = event.data.theme;
      univerAPI.toggleDarkMode(event.data.theme === "dark");
    }
  });
  window.addEventListener("keydown", event => {
    if (event.key === "Escape") post("close-request");
  });
  window.addEventListener("beforeunload", event => {
    if (dirty) { event.preventDefault();event.returnValue = ""; }
  });
  window.addEventListener("pagehide", () => {
    window.clearTimeout(comparisonTimer);
    listener.dispose();lifecycle.dispose();univer.dispose();
  });
  if (settled) ready();
}
