#!/usr/bin/env node
// Print changed generated contracts without turning a failed consistency gate green.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { closeSync, constants, fstatSync, lstatSync, openSync, readSync, readdirSync, realpathSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repository = realpathSync(resolve(here, "../../.."));
const directory = resolve(repository, "frontend/src/api/generated");
const maximumFileBytes = 1024 * 1024;
const maximumTotalBytes = 4 * maximumFileBytes;
function validatePaths() {
  let path = repository;
  for (const part of ["frontend", "src", "api", "generated"]) {
    path = resolve(path, part);
    const stat = lstatSync(path);
    if (!stat.isDirectory() || stat.isSymbolicLink() || realpathSync(path) !== path)
      throw new Error("Generated contract directory must be a real checkout directory");
  }
  const names = readdirSync(directory).sort();
  for (const name of names) {
    if (!name.endsWith(".ts")) continue;
    const stat = lstatSync(resolve(directory, name));
    if (!/^[a-z0-9-]+\.ts$/.test(name) || !stat.isFile() || stat.isSymbolicLink())
      throw new Error("Generated contract must be a regular named TypeScript file");
  }
  return names.filter(name => name.endsWith(".ts"));
}
function readBounded(file) {
  const fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const stat = fstatSync(fd);
    // This diagnostic runs only on the Linux CI runner. Check the opened object before reading.
    if (!stat.isFile() || stat.size > maximumFileBytes || realpathSync("/proc/self/fd/" + fd) !== file)
      throw new Error("Generated contract diagnostic file is outside its bound");
    const buffer = Buffer.alloc(maximumFileBytes + 1);
    let count = 0;
    while (count < buffer.length) {
      const read = readSync(fd, buffer, count, buffer.length - count, null);
      if (read === 0) break;
      count += read;
    }
    if (count > maximumFileBytes) throw new Error("Generated contract diagnostic size exceeded");
    return buffer.subarray(0, count);
  } finally { closeSync(fd); }
}
const names = validatePaths();
if (process.argv.includes("--preflight")) {
  console.log("Generated contract paths passed preflight");
} else {
  const changed = [];
  let total = 0;
  for (const name of names) {
    const bytes = readBounded(resolve(directory, name));
    const path = "frontend/src/api/generated/" + name;
    const tracked = execFileSync("git", ["ls-files", "--", path], {
      cwd: repository, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: maximumFileBytes,
    });
    const original = tracked.trim() ? execFileSync("git", ["show", "HEAD:" + path], {
      cwd: repository, stdio: ["ignore", "pipe", "pipe"], maxBuffer: maximumFileBytes * 2,
    }) : null;
    if (original && bytes.equals(original)) continue;
    total += bytes.length;
    if (total > maximumTotalBytes) throw new Error("Generated contract diagnostic total exceeded");
    changed.push({ path, bytes });
  }
  validatePaths();
  for (const { path, bytes } of changed) {
    const encoded = bytes.toString("base64");
    const chunks = Math.ceil(encoded.length / 12000);
    console.log("CONTRACT_GENERATION_FILE " + JSON.stringify({ path, bytes: bytes.length,
      sha256: createHash("sha256").update(bytes).digest("hex"), chunks }));
    for (let index = 0; index < chunks; index++)
      console.log("CONTRACT_GENERATION_CHUNK " + JSON.stringify({ path, index, data: encoded.slice(index * 12000, (index + 1) * 12000) }));
  }
  console.log("CONTRACT_GENERATION_COMPLETE " + JSON.stringify({ files: changed.length, bytes: total }));
}
