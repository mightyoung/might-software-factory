#!/usr/bin/env node
// 从 contracts/openapi/*.yaml 生成前端类型到 src/api/generated/*.ts。
//   node generate.mjs          生成（覆盖）
//   node generate.mjs --check  只比对：与已提交的结果不一致（含新增 / 缺失 / 多余文件）则退出码 1
// 生成物只是类型，不含运行时代码；不是手写的，不要手改。
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const contractsDir = resolve(here, "../../../contracts/openapi");
const outDir = resolve(here, "../../src/api/generated");
const bin = join(here, "node_modules/.bin/openapi-typescript");
const check = process.argv.includes("--check");

const contracts = readdirSync(contractsDir)
  .filter((name) => name.endsWith(".yaml"))
  .sort();
if (contracts.length === 0) {
  console.error(`在 ${contractsDir} 没有找到契约文件`);
  process.exit(2);
}

const target = check ? mkdtempSync(join(tmpdir(), "api-types-")) : outDir;
mkdirSync(target, { recursive: true });

const generated = new Set();
for (const name of contracts) {
  const out = `${name.replace(/\.yaml$/, "")}.ts`;
  generated.add(out);
  execFileSync(bin, [join(contractsDir, name), "-o", join(target, out)], { stdio: ["ignore", "ignore", "inherit"] });
  const body = readFileSync(join(target, out), "utf8");
  const banner = `// 由 frontend/tools/api-types 根据 contracts/openapi/${name} 生成，请勿手改。\n// 契约变更后运行：npm run gen:api（在 frontend 目录）。\n`;
  writeFileSync(join(target, out), banner + body);
}

if (!check) {
  // 删除契约后留下的过期生成物也要清掉，否则类型会悄悄引用已不存在的契约。
  for (const file of readdirSync(outDir)) {
    if (file.endsWith(".ts") && !generated.has(file)) rmSync(join(outDir, file));
  }
  console.log(`已生成 ${contracts.length} 个文件到 ${outDir}`);
  process.exit(0);
}

const problems = [];
const committed = existsSync(outDir) ? readdirSync(outDir).filter((file) => file.endsWith(".ts")) : [];
for (const file of generated) {
  if (!committed.includes(file)) problems.push(`缺少生成物：${file}（契约新增后没有运行 npm run gen:api）`);
  else if (readFileSync(join(outDir, file), "utf8") !== readFileSync(join(target, file), "utf8")) {
    problems.push(`已过期：${file}（契约变更后没有重新生成）`);
  }
}
for (const file of committed) {
  if (!generated.has(file)) problems.push(`多余的生成物：${file}（对应契约已不存在）`);
}
rmSync(target, { recursive: true, force: true });
if (problems.length > 0) {
  console.error("契约与前端生成类型不一致：\n- " + problems.join("\n- ") + "\n请在 frontend 目录运行 npm run gen:api 并提交结果。");
  process.exit(1);
}
console.log(`契约与前端生成类型一致（${contracts.length} 个文件）`);
