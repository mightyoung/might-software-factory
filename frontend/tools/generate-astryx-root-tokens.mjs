import { readFileSync, writeFileSync } from "node:fs";
import { mightTheme } from "../src/themes/might/might.js";

// CSS @scope is newer than this workbench's minimum browsers. The document root
// needs the same brand aliases there; compiled Astryx remains their sole source.
const tokens = Object.entries(mightTheme.tokens);
if (mightTheme.name !== "might" || tokens.some(([key, value]) =>
  !/^--[a-z0-9-]+$/.test(key) || typeof value !== "string" || !/^(?:var\(--[a-z0-9-]+\)|\d+px)$/.test(value)))
  throw new Error("Expected the reviewed MSF root aliases and pixel tokens");
const path = new URL("../src/themes/might/might.fallback.css", import.meta.url);
const css = "/* Generated from the compiled Might theme. Do not edit. */\n"
  + '@layer astryx-theme {\n  html[data-astryx-theme="might"] {\n'
  + tokens.map(([key, value]) => `    ${key}: ${value};\n`).join("")
  + "  }\n}\n";
const args = process.argv.slice(2);
if (args.length === 1 && args[0] === "--check") {
  if (readFileSync(path, "utf8") !== css) throw new Error("Might Astryx fallback is stale");
} else if (!args.length) writeFileSync(path, css);
else throw new Error("Only --check is supported");
