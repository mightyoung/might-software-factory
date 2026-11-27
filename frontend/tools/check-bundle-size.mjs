// Measure every emitted JS/CSS chunk, including deferred language resources, in KiB gzip.
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";
import { readGzipBundleGraph } from "./measure-bundle-graph.mjs";

const dist = join(import.meta.dirname, "..", "dist");
const editorChunk = file => file.startsWith("LedgerConfigPage-") || file.startsWith("PermissionConfigPage-");
const recordsChunk = file => file.startsWith("PublishedLedgersPage-");
const bundles = [
  { name: "工作台核心（含 Astryx）", directory: "assets", include: file => !editorChunk(file) && !recordsChunk(file) && !file.startsWith("OfficeDialog-"), limits: { js: 246, css: 56 } },
  { name: "按需载入的 Office 对话框", directory: "assets", include: file => file.startsWith("OfficeDialog-"), limits: { js: 1.5, css: 1 } },
  { name: "按需载入的台账与权限配置页", directory: "assets", include: editorChunk, limits: { js: 9, css: 1.5 } },
  { name: "按需载入的业务台账页", directory: "assets", include: recordsChunk, limits: { js: 6, css: 1.5 } },
  { name: "工作台合计", directory: "assets", limits: { js: 260, css: 56 } },
  { name: "Office 表格及文档（共享资源只计一次）", directory: "office/assets", limits: { js: 2880, css: 36 } },
];

let failed = false;
function report(name, totals, limits, allowZero = false) {
  for (const kind of ["js", "css"]) {
    const size = totals[kind] / 1024;
    const limitBytes = limits[kind] * 1024;
    const marginBytes = limitBytes - totals[kind];
    const ok = (allowZero || totals[kind] > 0) && marginBytes >= 0;
    failed ||= !ok;
    const signedMargin = `${marginBytes >= 0 ? "+" : ""}${marginBytes}`;
    console.log(`${ok ? "OK" : "FAIL"}  ${name} ${kind.toUpperCase()} ${size.toFixed(1)} / ${limits[kind]} KiB gzip (${totals[kind]} / ${limitBytes} B; margin ${signedMargin} B)`);
  }
}
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
  report(bundle.name, totals, bundle.limits);
}
const graph = readGzipBundleGraph(dist);
report("首屏静态依赖（含共享包）", graph.initial, { js: 235, css: 56 });
for (const [label, entry, limits] of [
  ["业务台账完整按需依赖", "src/features/publishedLedgers/PublishedLedgersPage.tsx", { js: 6, css: 1.5 }],
  ["台账配置完整按需依赖", "src/features/platform/LedgerConfigPage.ts", { js: 10, css: 1.5 }],
  ["Office 对话框完整按需依赖", "src/features/office/OfficeDialog.tsx", { js: 1.5, css: 1 }],
]) {
  // An accidental eager import or missing entry cannot silently reduce the deferred sum.
  const totals = graph.deferred[entry];
  if (!totals || totals.js === 0) throw new Error(`Expected deferred dependency: ${entry}`);
  report(label, totals, limits, true);
}
if (failed) process.exitCode = 1;
