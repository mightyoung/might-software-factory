import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const script = fileURLToPath(new URL("./upgrade-astryx.mjs", import.meta.url));

function withFixture(fn) {
  const root = mkdtempSync(join(tmpdir(), "msf-astryx-plan-"));
  const pkg = JSON.stringify({ name: "might-software-factory-frontend", dependencies: { "@astryxdesign/core": "0.7.16" } });
  const lock = JSON.stringify({ packages: { "": JSON.parse(pkg), "node_modules/@astryxdesign/core": { version: "0.7.16" } } });
  try {
    writeFileSync(join(root, "package.json"), pkg);
    writeFileSync(join(root, "package-lock.json"), lock);
    const run = (...args) => spawnSync(process.execPath, [script, ...args], { cwd: root, encoding: "utf8" });
    fn(run);
    assert.deepEqual(readdirSync(root).sort(), ["package-lock.json", "package.json"]);
    assert.equal(readFileSync(join(root, "package.json"), "utf8"), pkg);
    assert.equal(readFileSync(join(root, "package-lock.json"), "utf8"), lock);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test("dry-run shows exact package alignment, theme generation and both browser gates without writes", () => {
  withFixture(run => {
    const result = run("--dry-run", "--target=0.7.16");
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /@astryxdesign\/core: 0\.7\.16 -> 0\.7\.16/);
    assert.match(result.stdout, /@astryxdesign\/theme-neutral: 未接入 -> 0\.7\.16/);
    assert.match(result.stdout, /astryx theme build/);
    assert.match(result.stdout, /E2E_PREVIEW=http/);
    assert.match(result.stdout, /E2E_PREVIEW=synthetic/);
  });
});

test("unknown args, unsupported semver, and apply without the theme integration fail closed", () => {
  withFixture(run => {
    assert.notEqual(run("--dry-run", "--target=latest").status, 0);
    assert.notEqual(run("--dry-run", "--target=0.7.16", "--target=0.7.18").status, 0);
    assert.notEqual(run("--target=0.7.16").status, 0);
    const unavailable = run("--apply", "--target=0.7.16");
    assert.notEqual(unavailable.status, 0);
    assert.match(unavailable.stderr, /主题接缝未接入/);
  });
});
