import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

const root = join(import.meta.dirname, "dist");
const files = readdirSync(root, { recursive: true }).filter((name) => /\.(?:js|css|wasm)$/.test(name));
if (!files.some((name) => name.endsWith(".js"))) throw new Error("Expected independently built 3D JavaScript");
const size = files.reduce((total, name) => total + gzipSync(readFileSync(join(root, name))).length, 0);
const maximum = 280 * 1024;
console.log(`3D preview JS/CSS/WASM gzip: ${size} / ${maximum} B (${files.length} assets)`);
if (size > maximum) process.exitCode = 1;
