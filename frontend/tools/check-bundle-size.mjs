// 构建产物体积预算（gzip）。超出就失败：新增依赖或大块代码要有意识地提高预算并写明原因。
// 预算是工程门槛，不是性能结论：首屏时间要在真实网络与后端上测。
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const BUDGET_KB = { js: 130, css: 12 }; // 2026-11：JS≈93KB、CSS≈5KB，留约 40% / 2 倍余量
const dir = join(import.meta.dirname, "..", "dist", "assets");
const totals = { js: 0, css: 0 };

for (const file of readdirSync(dir)) {
  const kind = file.endsWith(".js") ? "js" : file.endsWith(".css") ? "css" : null;
  if (kind) totals[kind] += gzipSync(readFileSync(join(dir, file))).length;
}

let failed = false;
for (const kind of ["js", "css"]) {
  const kb = totals[kind] / 1024;
  const ok = kb <= BUDGET_KB[kind];
  failed ||= !ok;
  console.log(`${ok ? "OK  " : "FAIL"} ${kind.toUpperCase()} gzip ${kb.toFixed(1)} KB / 预算 ${BUDGET_KB[kind]} KB`);
}
if (failed) process.exit(1);
