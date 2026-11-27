// Run the emitted entry, not source transformed by Vitest: static ESM import
// ordering can break a production build even when the bundle budget passes.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { setTimeout } from "node:timers/promises";
import { JSDOM } from "jsdom";

const mode = process.argv[2];
assert.ok(mode === "synthetic" || mode === "http", "Specify synthetic or http");
const dist = new URL("../dist/", import.meta.url);
const dom = new JSDOM(await readFile(new URL("index.html", dist), "utf8"), {
  url: "http://127.0.0.1:15173/",
  pretendToBeVisual: true,
});
const { window } = dom;
try {
  for (const key of ["window", "document", "location", "localStorage", "sessionStorage", "HTMLElement", "Node",
    "MutationObserver", "navigator", "Event", "PopStateEvent", "history", "getComputedStyle", "requestAnimationFrame", "cancelAnimationFrame"]) {
    const value = key === "window" ? window : window[key];
    Object.defineProperty(globalThis, key, { configurable: true, value: typeof value === "function" && /^[a-z]/.test(key)
      ? value.bind(window) : value });
  }
  // jsdom lacks native modulepreload; keep the smoke test strictly offline.
  globalThis.fetch = async (input) => new URL(String(input), window.location.href).pathname.startsWith("/api/")
    ? Response.json({ code: "AUTH_REQUIRED" }, { status: 401 })
    : new Response(null, { status: 200 });
  const entry = window.document.querySelector('script[type="module"][src]')?.getAttribute("src");
  assert.match(entry ?? "", /^\/assets\/[\w.-]+\.js$/, "Expected local emitted entry");
  await import(new URL("." + entry, dist));
  assert.equal(window.document.title, mode === "synthetic" ? "MIGHT 工厂安全 · 演示" : "MIGHT 工厂安全");
  for (let n = 0; n < 50 && window.document.querySelector("#root h1")?.textContent !== "登录"; n += 1) await setTimeout(20);
  assert.equal(window.document.querySelector("#root h1")?.textContent, "登录");
  console.log(`Production ${mode} entry and login booted`);
} catch (error) {
  console.error(error.stack);
  process.exitCode = 1;
} finally {
  window.close();
}
