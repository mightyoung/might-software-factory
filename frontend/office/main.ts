import { createUniver, LocaleType, mergeLocales } from "@univerjs/presets";
import { UniverSheetsCorePreset } from "@univerjs/preset-sheets-core";
import zhCN from "@univerjs/preset-sheets-core/locales/zh-CN";
import type { IWorkbookData } from "@univerjs/core";
import "@univerjs/preset-sheets-core/lib/index.css";
import "./office.css";

import { startLocalDocumentSession, validateJsonStructure } from "./local-document-session";

const editor = document.querySelector<HTMLDivElement>("#editor")!;
const dark = new URLSearchParams(location.search).get("theme") === "dark";
document.documentElement.dataset.theme = dark ? "dark" : "light";
const { univer, univerAPI } = createUniver({
  darkMode: dark,
  locale: LocaleType.ZH_CN,
  locales: { [LocaleType.ZH_CN]: mergeLocales(zhCN) },
  presets: [UniverSheetsCorePreset({ container: editor })],
});
const workbook = univerAPI.createWorkbook({ name: "未命名工作簿" });
const workbookVersion = workbook.save().appVersion;

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
  validateJsonStructure(value, "工作簿");
  return value as IWorkbookData;
}

startLocalDocumentSession({
  univer, univerAPI, unit: workbook, name: "工作簿",
  create: snapshot => univerAPI.createWorkbook(snapshot),
  validate: validateSnapshot,
  serialize: snapshot => JSON.stringify(snapshot, null, 2),
  filename: snapshot => snapshot.name,
});
