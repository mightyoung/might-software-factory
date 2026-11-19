import { createUniver, LocaleType, mergeLocales } from "@univerjs/presets";
import { UniverSheetsCorePreset } from "@univerjs/preset-sheets-core";
import zhCN from "@univerjs/preset-sheets-core/locales/zh-CN";
import { CommandType, type IWorkbookData } from "@univerjs/core";
import "@univerjs/preset-sheets-core/lib/index.css";
import "./office.css";

const channel = "might-office-v1";
const editor = document.querySelector<HTMLDivElement>("#editor")!;
const status = document.querySelector<HTMLElement>("#status")!;
const error = document.querySelector<HTMLElement>("#error")!;
const fileInput = document.querySelector<HTMLInputElement>("#file")!;
const importButton = document.querySelector<HTMLButtonElement>("#import")!;
const exportButton = document.querySelector<HTMLButtonElement>("#export")!;
const dark = new URLSearchParams(location.search).get("theme") === "dark";
document.documentElement.dataset.theme = dark ? "dark" : "light";
const { univer, univerAPI } = createUniver({
  darkMode: dark,
  locale: LocaleType.ZH_CN,
  locales: { [LocaleType.ZH_CN]: mergeLocales(zhCN) },
  presets: [UniverSheetsCorePreset({ container: editor })],
});
let workbook = univerAPI.createWorkbook({ name: "未命名工作簿" });
const workbookVersion = workbook.save().appVersion;
let baseline = JSON.stringify(workbook.save());
let dirty = false;
let importedFile = "";
let comparisonTimer = 0;
let busy = false;
let settled = univerAPI.getCurrentLifecycleStage() >= univerAPI.Enum.LifecycleStages.Steady;

function post(type: string, extra: Record<string, unknown> = {}) {
  if (parent !== window) parent.postMessage({ channel, type, ...extra }, location.origin);
}
function ready() {
  baseline = JSON.stringify(workbook.save());
  update();importButton.disabled = false;exportButton.disabled = false;post("ready");
}
function update() {
  dirty = JSON.stringify(workbook.save()) !== baseline;
  const contentState = dirty ? "有尚未导出的修改" : "工作簿已就绪";
  status.textContent = importedFile ? `已导入 ${importedFile}，${contentState}` : contentState;
  post("state", { dirty });
}
function showError(message: string) {
  error.textContent = message;
  error.hidden = false;
}
function validateSnapshot(value: unknown): IWorkbookData {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("工作簿必须是 JSON 对象。");
  const data = value as Record<string, unknown>;
  if (data.appVersion !== workbookVersion) throw new Error(`仅支持 Univer ${workbookVersion} 原生工作簿。`);
  if (typeof data.id !== "string" || !data.id || typeof data.name !== "string"
    || !Array.isArray(data.sheetOrder) || data.sheetOrder.length < 1 || data.sheetOrder.length > 50
    || !data.sheets || typeof data.sheets !== "object" || Array.isArray(data.sheets)) {
    throw new Error("文件缺少有效的工作簿标识、名称或工作表。");
  }
  const sheets = data.sheets as Record<string, unknown>;
  if (new Set(data.sheetOrder).size !== data.sheetOrder.length || Object.keys(sheets).length !== data.sheetOrder.length) {
    throw new Error("工作表标识重复或内容不一致。");
  }
  for (const id of data.sheetOrder) {
    if (typeof id !== "string" || !Object.hasOwn(sheets, id)) throw new Error("工作表顺序与内容不一致。");
    const sheet = sheets[id] as Record<string, unknown>;
    if (!sheet || typeof sheet !== "object" || sheet.id !== id || typeof sheet.name !== "string"
      || !Number.isInteger(sheet.rowCount) || Number(sheet.rowCount) < 1 || Number(sheet.rowCount) > 100000
      || !Number.isInteger(sheet.columnCount) || Number(sheet.columnCount) < 1 || Number(sheet.columnCount) > 1000) {
      throw new Error("工作表结构无效或超出 100000 行、1000 列限制。");
    }
    if (sheet.cellData !== undefined) {
      if (!sheet.cellData || typeof sheet.cellData !== "object" || Array.isArray(sheet.cellData)) throw new Error("单元格内容结构无效。");
      for (const [row, columns] of Object.entries(sheet.cellData)) {
        if (!/^\d+$/.test(row) || Number(row) >= Number(sheet.rowCount) || !columns || typeof columns !== "object" || Array.isArray(columns)) {
          throw new Error("单元格行超出工作表范围。");
        }
        if (Object.keys(columns).some(column => !/^\d+$/.test(column) || Number(column) >= Number(sheet.columnCount))) {
          throw new Error("单元格列超出工作表范围。");
        }
      }
    }
  }
  let nodes = 0;
  function inspect(node: unknown, depth: number) {
    if (++nodes > 500000 || depth > 40) throw new Error("工作簿结构过于复杂。");
    if (!node || typeof node !== "object") return;
    for (const [key, child] of Object.entries(node)) {
      if (["__proto__", "constructor", "prototype"].includes(key)) throw new Error("工作簿包含无效属性。");
      inspect(child, depth + 1);
    }
  }
  inspect(value, 0);
  return value as IWorkbookData;
}

const listener = univerAPI.addEvent(univerAPI.Event.CommandExecuted, event => {
  if (event.type !== CommandType.MUTATION || !settled || busy) return;
  dirty = true;
  // A mutation can also be an internal recalculation. Compare after its batch settles.
  window.clearTimeout(comparisonTimer);
  comparisonTimer = window.setTimeout(update, 80);
});
const lifecycle = univerAPI.addEvent(univerAPI.Event.LifeCycleChanged, ({ stage }) => {
  if (!settled && stage === univerAPI.Enum.LifecycleStages.Steady) {
    settled = true;ready();
  }
});
importButton.addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  fileInput.value = "";
  if (!file || busy) return;
  error.hidden = true;
  busy = true;importButton.disabled = true;exportButton.disabled = true;
  try {
    if (file.size > 5 * 1024 * 1024) throw new Error("请选择不超过 5 MiB 的工作簿 JSON 文件。");
    const snapshot = validateSnapshot(JSON.parse(await file.text()));
    update();
    if (dirty && !window.confirm("当前修改尚未导出。确认丢弃并导入另一份工作簿？")) return;
    const previous = workbook.save();
    univerAPI.disposeUnit(workbook.getId());
    try {
      workbook = univerAPI.createWorkbook(snapshot);
    } catch (failure) {
      workbook = univerAPI.createWorkbook(previous);
      throw failure;
    }
    baseline = JSON.stringify(workbook.save());
    importedFile = file.name;
    update();
  } catch (failure) {
    showError(failure instanceof Error ? failure.message : "工作簿导入失败，请核对文件。");
  } finally {
    busy = false;importButton.disabled = false;exportButton.disabled = false;
  }
});
exportButton.addEventListener("click", () => {
  try {
    const snapshot = workbook.save();
    const serialized = JSON.stringify(snapshot, null, 2);
    const url = URL.createObjectURL(new Blob([serialized], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${snapshot.name.replace(/[\\/:*?"<>|]/g, "_") || "工作簿"}.json`;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    baseline = JSON.stringify(snapshot);update();status.textContent = "已发起本机文件下载，请确认文件已保存";
  } catch {
    showError("工作簿导出失败，请重试。");
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
