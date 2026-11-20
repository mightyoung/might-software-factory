// Measure every emitted JS/CSS chunk, including deferred language resources, in KiB gzip.
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const dist = join(import.meta.dirname, "..", "dist");
const editorChunk = file => file.startsWith("LedgerConfigPage-") || file.startsWith("PermissionConfigPage-");
const recordsChunk = file => file.startsWith("PublishedLedgersPage-");
const bundles = [
  { name: "工作台核心（含 Astryx）", directory: "assets", include: file => !editorChunk(file) && !recordsChunk(file) && !file.startsWith("OfficeDialog-"), limits: { js: 202, css: 56 } },
  { name: "按需载入的 Office 对话框", directory: "assets", include: file => file.startsWith("OfficeDialog-"), limits: { js: 1.5, css: 1 } },
  { name: "按需载入的台账与权限配置页", directory: "assets", include: editorChunk, limits: { js: 9, css: 1.5 } },
  { name: "按需载入的业务台账页", directory: "assets", include: recordsChunk, limits: { js: 6, css: 1.5 } },
  { name: "工作台合计", directory: "assets", limits: { js: 202, css: 56 } },
  { name: "Office 表格及文档（共享资源只计一次）", directory: "office/assets", limits: { js: 2880, css: 36 } },
];

let failed = false;
for (const bundle of bundles) {
  const dir = join(dist, bundle.directory);
  const totals = { js: 0, css: 0 };
  for (const file of readdirSync(dir)) {
    if (bundle.include && !bundle.include(file)) continue;
    const kind = file.split(".").pop();
    if (kind === "js" || kind === "css") {
      totals[kind] += gzipSync(readFileSync(join(dir, file))).length;
    }
  }
  for (const kind of ["js", "css"]) {
    const size = totals[kind] / 1024;
    const ok = size > 0 && size <= bundle.limits[kind];
    failed ||= !ok;
    const limitBytes = bundle.limits[kind] * 1024;
    const marginBytes = limitBytes - totals[kind];
    const signedMargin = `${marginBytes >= 0 ? "+" : ""}${marginBytes}`;
    console.log(`${ok ? "OK" : "FAIL"}  ${bundle.name} ${kind.toUpperCase()} ${size.toFixed(1)} / ${bundle.limits[kind]} KiB gzip (${totals[kind]} / ${limitBytes} B; margin ${signedMargin} B)`);
  }
}
if (failed) process.exitCode = 1;
