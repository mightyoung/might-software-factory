import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Agent } from "node:https";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
const root = fileURLToPath(new URL("../../frontend/", import.meta.url));
const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
const { preview } = await import(pathToFileURL(require.resolve("vite/package.json"))
  .href.replace(/package\.json$/, "dist/node/index.js"));
const { TL10_BACKEND: backend, TL10_PFX: pfx, TL10_CA: ca, TL10_TLS_PASSWORD: passphrase } = process.env;
assert.match(backend ?? "", /^https:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/);
const server = await preview({
  root, configFile: false, build: { outDir: "dist" },
  preview: {
    host: "127.0.0.1", port: 0, strictPort: true,
    https: { pfx: readFileSync(pfx), passphrase },
    proxy: { "/api/": { target: backend, agent: new Agent({ ca: readFileSync(ca) }) } },
  },
});
const { address, port } = server.httpServer.address();
assert.equal(address, "127.0.0.1");
console.log("TL10_PREVIEW_PORT=" + port);
