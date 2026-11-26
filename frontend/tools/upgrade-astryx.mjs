#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";

const packages = ["@astryxdesign/core", "@astryxdesign/theme-neutral", "@astryxdesign/cli"];
const theme = "src/themes/might/mightTheme.ts";
const targetPattern = /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;
const read = path => JSON.parse(readFileSync(path, "utf8"));

function fail(message) { throw new Error(message); }

function run(command, args, env = {}, capture = false) {
  const result = spawnSync(command, args, { env: { ...process.env, ...env },
    stdio: capture ? ["ignore", "pipe", "inherit"] : "inherit", encoding: "utf8" });
  if (result.status !== 0) fail(`步骤失败：${command} ${args.join(" ")}`);
  return result.stdout?.trim();
}

function lockVersion(pkg, lock, name) {
  const declared = pkg.dependencies?.[name] ?? pkg.devDependencies?.[name];
  if (!declared) return null;
  if (!targetPattern.test(declared) || lock.packages?.[`node_modules/${name}`]?.version !== declared)
    fail(`${name} 需要一致的精确声明与 lock 版本`);
  return declared;
}

function main(argv) {
  const mode = argv.find(arg => arg === "--apply" || arg === "--dry-run");
  const target = argv.find(arg => arg.startsWith("--target="))?.slice("--target=".length);
  if (argv.length !== 2 || !mode || !target || !targetPattern.test(target))
    fail("用法：在 frontend 下运行 node tools/upgrade-astryx.mjs --target=<精确版本> --dry-run（或 --apply）");
  const pkg = read("package.json");
  if (pkg.name !== "might-software-factory-frontend") fail("需要 MSF 前端工作目录");
  const lock = read("package-lock.json");
  const current = packages.map(name => lockVersion(pkg, lock, name));
  const ready = [theme, theme.replace(/\.ts$/, ".doc.mjs"), "src/astryx-themes.ts"].every(existsSync);
  const atTarget = name => `${name}@${target}`;
  const steps = [
    ["npm", ["install", "--package-lock-only", "--ignore-scripts", "--save-exact", ...packages.slice(0, 2).map(atTarget)]],
    ["npm", ["install", "--package-lock-only", "--ignore-scripts", "--save-dev", "--save-exact", atTarget(packages[2])]],
    ["npm", ["ci", "--no-audit", "--no-fund"]],
    ["npm", ["exec", "--offline", "--", "astryx", "theme", "build", theme]],
    [process.execPath, ["tools/generate-astryx-root-tokens.mjs"]],
    ["npm", ["run", "typecheck"]],
    ["npm", ["test"]],
    ...["http", "synthetic"].flatMap(mode => [
      ["npm", ["run", "build"], { VITE_API_MODE: mode }],
      ["npm", ["run", "check:bundle"]],
      ["npm", ["run", "e2e"], { E2E_PREVIEW: mode }],
    ]),
  ];
  packages.forEach((name, index) => console.log(`${name}: ${current[index] ?? "未接入"} -> ${target}`));
  for (const [cmd, args, env = {}] of steps) console.log([...
    Object.entries(env).map(pair => pair.join("=")), cmd, ...args].join(" "));
  if (mode === "--dry-run") {
    console.log(ready ? "仅预览，未启动命令，也未修改文件。" : "主题接缝未接入；仅预览，未启动命令，也未修改文件。");
    return;
  }
  if (!ready) fail("主题接缝未接入；先完成主题构建链并通过双主题验证");
  const branch = run("git", ["branch", "--show-current"], {}, true);
  if (!branch || ["dev", "main", "master"].includes(branch)
      || run("git", ["status", "--porcelain", "--untracked-files=all"], {}, true)
      || run("git", ["rev-parse", "--path-format=absolute", "--git-dir"], {}, true)
         === run("git", ["rev-parse", "--path-format=absolute", "--git-common-dir"], {}, true))
    fail("正式升级需要干净的独立工作树以及非 dev/main 的主题分支");
  for (const [cmd, args, env] of steps) {
    run(cmd, args, env);
    if (args.includes("--save-dev")) {
      const next = read("package.json"), nextLock = read("package-lock.json");
      if (packages.some(name => lockVersion(next, nextLock, name) !== target)) fail("三项依赖未锁定到同一目标版本");
    }
  }
  console.log("升级及双模式门禁通过；请评审差异后再提交。");
}

try { main(process.argv.slice(2)); }
catch (error) { console.error(error.message); process.exitCode = 1; }
