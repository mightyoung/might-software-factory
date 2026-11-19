// Measure every emitted JS/CSS chunk, including deferred language resources, in KiB gzip.
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const dist = join(import.meta.dirname, "..", "dist");
const bundles = [
  { name: "工作台（含 Astryx）", directory: "assets", limits: { js: 192, css: 56 } },
  { name: "独立 Office 编辑器", directory: "office/assets", limits: { js: 2880, css: 24 } },
];

let failed = false;
for (const bundle of bundles) {
  const dir = join(dist, bundle.directory);
  const totals = { js: 0, css: 0 };
  for (const file of readdirSync(dir)) {
    const kind = file.split(".").pop();
    if (kind === "js" || kind === "css") {
      totals[kind] += gzipSync(readFileSync(join(dir, file))).length;
    }
  }
  for (const kind of ["js", "css"]) {
    const size = totals[kind] / 1024;
    const ok = size > 0 && size <= bundle.limits[kind];
    failed ||= !ok;
    console.log(`${ok ? "OK" : "FAIL"}  ${bundle.name} ${kind.toUpperCase()} ${size.toFixed(1)} / ${bundle.limits[kind]} KiB gzip`);
  }
}
if (failed) process.exitCode = 1;
