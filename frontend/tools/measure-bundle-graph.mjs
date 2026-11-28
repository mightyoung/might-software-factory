import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { gzipSync } from "node:zlib";

/** Static dependency closures; deferred sizes are additional to the initial shell. */
export function summarizeBundleGraph(manifest, sizeOf) {
  const get = (key) => {
    if (!Object.hasOwn(manifest, key)) throw new Error(`Missing manifest chunk: ${key}`);
    return manifest[key];
  };
  const closure = (root, followDynamic, onDynamic = () => {}) => {
    const files = new Set();
    const visited = new Set();
    function visit(key) {
      if (visited.has(key)) return;
      visited.add(key);
      const chunk = get(key);
      if (chunk.file) files.add(chunk.file);
      for (const css of chunk.css ?? []) files.add(css);
      for (const child of chunk.imports ?? []) visit(child);
      if (followDynamic) {
        for (const child of chunk.dynamicImports ?? []) {
          onDynamic(child);
          visit(child);
        }
      }
    }
    visit(root);
    return files;
  };
  const measure = (files) => {
    const bytes = { js: 0, css: 0 };
    for (const file of files) {
      const kind = file.endsWith(".css") ? "css" : /\.[cm]?js$/.test(file) ? "js" : null;
      if (!kind) continue;
      const size = sizeOf(file);
      if (!Number.isSafeInteger(size) || size <= 0) throw new Error(`Missing or invalid size: ${file}`);
      bytes[kind] += size;
    }
    return bytes;
  };
  const deferred = new Set();
  if (!get("index.html").isEntry) throw new Error("index.html is not a build entry");
  closure("index.html", true, (key) => deferred.add(key));
  const initial = closure("index.html", false);
  return {
    initial: measure(initial),
    deferred: Object.fromEntries([...deferred].sort().map((key) => [
      key, measure([...closure(key, false)].filter((file) => !initial.has(file))),
    ])),
  };
}

export function readGzipBundleGraph(dist) {
  const manifest = JSON.parse(readFileSync(join(dist, ".vite", "manifest.json"), "utf8"));
  return summarizeBundleGraph(manifest, (file) => gzipSync(readFileSync(join(dist, file))).length);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const dist = resolve(process.argv[2] ?? join(import.meta.dirname, "..", "dist"));
  console.log(JSON.stringify(readGzipBundleGraph(dist), null, 2));
}
