import { CommandType, type Univer } from "@univerjs/core";
import type { FUniver } from "@univerjs/core/facade";

interface LocalDocumentUnit<Snapshot> {
  getId(): string;
  getName(): string;
  setName(name: string): unknown;
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
  plainText?(snapshot: Snapshot): string;
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
  const nameInput = document.querySelector<HTMLInputElement>("#name")!;
  const exportButton = document.querySelector<HTMLButtonElement>("#export")!;
  const textButton = document.querySelector<HTMLButtonElement>("#export-text");
  let unit = session.unit;
  let baseline = JSON.stringify(unit.save());
  let dirty = false;
  let importedFile = "";
  let comparisonTimer = 0;
  let announcementSnapshot: string | null = null;
  let busy = false;
  let settled = univerAPI.getCurrentLifecycleStage() >= univerAPI.Enum.LifecycleStages.Steady;

  function post(type: string, extra: Record<string, unknown> = {}) {
    if (parent !== window) parent.postMessage({ channel, type, ...extra }, location.origin);
  }
  function update(passive = false) {
    if (document.activeElement !== nameInput) nameInput.value = unit.getName();
    const snapshot = JSON.stringify(unit.save());
    const pendingRename = nameInput.value.trim() !== unit.getName();
    dirty = snapshot !== baseline || pendingRename;
    // A settled comparison still reports dirty state, but must not erase the
    // export announcement for the exact same document.
    if (!passive || pendingRename || announcementSnapshot !== snapshot) {
      announcementSnapshot = null;
      const contentState = dirty ? "有尚未导出的修改" : `${name}已就绪`;
      status.textContent = importedFile ? `已导入 ${importedFile}，${contentState}` : contentState;
    }
    post("state", { dirty });
    return snapshot;
  }
  function ready() {
    baseline = JSON.stringify(unit.save());
    nameInput.value = unit.getName();
    update();
    importButton.disabled = false;
    exportButton.disabled = false;
    if (textButton) textButton.disabled = false;
    nameInput.disabled = false;
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
    comparisonTimer = window.setTimeout(() => update(true), 80);
  });
  const lifecycle = univerAPI.addEvent(univerAPI.Event.LifeCycleChanged, ({ stage }) => {
    if (!settled && stage === univerAPI.Enum.LifecycleStages.Steady) {
      settled = true;
      ready();
    }
  });
  function rename() {
    const nextName = nameInput.value.trim();
    if (!nextName) {
      nameInput.value = unit.getName();
      showError(`${name}名称不能为空。`);
      return;
    }
    try {
      unit.setName(nextName);
      nameInput.value = unit.getName();
      error.hidden = true;
      update();
    } catch {
      nameInput.value = unit.getName();
      showError(`${name}重命名失败，请重试。`);
    }
  }
  nameInput.addEventListener("input", () => update());
  nameInput.addEventListener("change", rename);
  nameInput.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.isComposing) {
      event.preventDefault();
      rename();
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
    if (textButton) textButton.disabled = true;
    nameInput.disabled = true;
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
      nameInput.value = unit.getName();
      importedFile = file.name;
      update();
    } catch (failure) {
      showError(failure instanceof Error ? failure.message : `${name}导入失败，请核对文件。`);
    } finally {
      busy = false;
      importButton.disabled = false;
      exportButton.disabled = false;
      if (textButton) textButton.disabled = false;
      nameInput.disabled = false;
    }
  });
  function download(snapshot: Snapshot, content: string, mimeType: string, extension: string) {
    const url = URL.createObjectURL(new Blob([content], { type: mimeType }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${session.filename(snapshot).replace(/[\u0000-\u001f\u007f\\/:*?"<>|]/g, "_").replace(/[. ]+$/g, "") || name}.${extension}`;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const plainText = session.plainText;
  if (textButton && plainText) {
    textButton.addEventListener("click", () => {
      try {
        const snapshot = unit.save();
        download(snapshot, plainText(snapshot), "text/plain;charset=utf-8", "txt");
        announcementSnapshot = update();
        status.textContent = `已发起 TXT 本机下载，请确认文件已保存。${dirty ? "文档仍有尚未导出为 JSON 的修改。" : ""}`;
      } catch {
        showError("纯文本导出失败，请重试。");
      }
    });
  }
  exportButton.addEventListener("click", () => {
    try {
      const snapshot = unit.save();
      download(snapshot, session.serialize(snapshot), "application/json", "json");
      baseline = JSON.stringify(snapshot);
      announcementSnapshot = update();
      status.textContent = "已发起本机文件下载，请确认文件已保存";
    } catch {
      showError(`${name}导出失败，请重试。`);
    }
  });
  window.addEventListener("message", event => {
    if (event.origin !== location.origin || event.source !== parent || event.data?.channel !== channel) return;
    if (event.data.type === "close-check") {
      if (nameInput.value.trim() !== unit.getName()) rename();
      update();post("close-state", { dirty, requestId: event.data.requestId });
    }
    if (event.data.type === "theme" && (event.data.theme === "dark" || event.data.theme === "light")) {
      document.documentElement.dataset.theme = event.data.theme;
      univerAPI.toggleDarkMode(event.data.theme === "dark");
    }
  });
  window.addEventListener("keydown", event => {
    if (event.key === "Escape") post("close-request");
  });
  window.addEventListener("beforeunload", event => {
    if (dirty || nameInput.value.trim() !== unit.getName()) { event.preventDefault();event.returnValue = ""; }
  });
  window.addEventListener("pagehide", () => {
    window.clearTimeout(comparisonTimer);
    listener.dispose();lifecycle.dispose();univer.dispose();
  });
  if (settled) ready();
}
