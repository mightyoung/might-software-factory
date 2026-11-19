import { createUniver, LocaleType, mergeLocales } from "@univerjs/presets";
import { UniverDocsCorePreset } from "@univerjs/preset-docs-core";
import zhCN from "@univerjs/preset-docs-core/locales/zh-CN";
import { getDocsEmptySnapshot, getPlainText, type IDocumentData } from "@univerjs/core";
import "@univerjs/preset-docs-core/lib/index.css";
import "./office.css";

import { startLocalDocumentSession, validateJsonStructure } from "./local-document-session";

const sdkVersion = "1.5.2";
const editor = document.querySelector<HTMLDivElement>("#editor")!;
const dark = new URLSearchParams(location.search).get("theme") === "dark";
document.documentElement.dataset.theme = dark ? "dark" : "light";
const { univer, univerAPI } = createUniver({
  darkMode: dark,
  locale: LocaleType.ZH_CN,
  locales: { [LocaleType.ZH_CN]: mergeLocales(zhCN) },
  presets: [UniverDocsCorePreset({ container: editor })],
});
const documentUnit = univerAPI.createDocument(getDocsEmptySnapshot(undefined, LocaleType.ZH_CN, "未命名文档"));

function validateSnapshot(value: unknown): IDocumentData {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("文档必须是 JSON 对象。");
  const envelope = value as Record<string, unknown>;
  if (envelope.format !== "might-office-document" || envelope.version !== 1 || envelope.sdkVersion !== sdkVersion) {
    throw new Error(`请选择当前编辑器导出的 Univer ${sdkVersion} 文档 JSON。`);
  }
  const data = envelope.document as Record<string, unknown> | undefined;
  const body = data?.body as Record<string, unknown> | undefined;
  if (!data || typeof data !== "object" || Array.isArray(data) || typeof data.id !== "string" || !data.id
    || typeof data.title !== "string" || !data.documentStyle || typeof data.documentStyle !== "object"
    || Array.isArray(data.documentStyle) || !body || typeof body !== "object" || Array.isArray(body)
    || typeof body.dataStream !== "string" || !body.dataStream.endsWith("\r\n")) {
    throw new Error("文件缺少有效的文档标识、标题、页面样式或正文。");
  }
  if (body.dataStream.length > 1000000) throw new Error("文档正文超过 100 万字符限制。");
  const pageSize = (data.documentStyle as Record<string, unknown>).pageSize as Record<string, unknown> | undefined;
  if (!pageSize || ![pageSize.width, pageSize.height].every(size => typeof size === "number" && Number.isFinite(size) && size > 0 && size <= 100000)) {
    throw new Error("文档页面尺寸无效。");
  }
  for (const key of ["paragraphs", "sectionBreaks"]) {
    const ranges = body[key];
    if (ranges === undefined) continue;
    if (!Array.isArray(ranges) || ranges.some(range => !range || typeof range !== "object"
      || !Number.isInteger(range.startIndex) || range.startIndex < 0 || range.startIndex >= (body.dataStream as string).length
      || (range.endIndex !== undefined && (!Number.isInteger(range.endIndex) || range.endIndex < range.startIndex || range.endIndex > (body.dataStream as string).length)))) {
      throw new Error("文档正文格式范围无效。");
    }
  }
  if (body.textRuns !== undefined && (!Array.isArray(body.textRuns) || body.textRuns.some(run => !run || typeof run !== "object"
    || !Number.isInteger(run.st) || !Number.isInteger(run.ed) || run.st < 0 || run.ed < run.st || run.ed > (body.dataStream as string).length))) {
    throw new Error("文档文字样式范围无效。");
  }
  validateJsonStructure(data, "文档");
  return data as unknown as IDocumentData;
}

startLocalDocumentSession({
  univer, univerAPI, unit: documentUnit, name: "文档",
  create: snapshot => univerAPI.createDocument(snapshot),
  validate: validateSnapshot,
  serialize: snapshot => JSON.stringify({ format: "might-office-document", version: 1, sdkVersion, document: snapshot }, null, 2),
  filename: snapshot => snapshot.title ?? "文档",
  plainText: snapshot => getPlainText(snapshot.body?.dataStream ?? "")
    .replace(/\r\n?|\v|\f|\u2028|\u2029/g, "\n")
    .replace(/[\u0000-\u0008\u000e-\u001f\u007f]/g, ""),
});
