import assert from "node:assert/strict";
import { test } from "node:test";
import { summarizeBundleGraph } from "./measure-bundle-graph.mjs";

const manifest = {
  "index.html": {
    file: "assets/root.js", isEntry: true,
    imports: ["_vendor.js"], css: ["assets/root.css"], dynamicImports: ["src/records.tsx", "src/admin.tsx"],
  },
  "_vendor.js": { file: "assets/vendor.js", css: ["assets/ui.css"] },
  "src/records.tsx": {
    file: "assets/records.js", imports: ["_vendor.js", "_records-model.js"], css: ["assets/ui.css"],
    isDynamicEntry: true, dynamicImports: ["src/attachments.tsx"],
  },
  "_records-model.js": { file: "assets/shared-edit.js" },
  "src/admin.tsx": { file: "assets/admin.js", imports: ["_records-model.js"], isDynamicEntry: true },
  "src/attachments.tsx": { file: "assets/attachments.js", imports: ["_vendor.js"], isDynamicEntry: true },
};
const size = (file) => ({
  "assets/root.js": 20, "assets/vendor.js": 40, "assets/records.js": 15,
  "assets/shared-edit.js": 8, "assets/admin.js": 10, "assets/attachments.js": 6,
  "assets/root.css": 5, "assets/ui.css": 3,
})[file];

test("reports initial bytes and each dynamic branch's extra static closure without double counting", () => {
  assert.deepEqual(summarizeBundleGraph(manifest, size), {
    initial: { js: 60, css: 8 },
    deferred: {
      "src/admin.tsx": { js: 18, css: 0 },
      "src/attachments.tsx": { js: 6, css: 0 },
      "src/records.tsx": { js: 23, css: 0 },
    },
  });
});
test("fails closed if a static or dynamic edge is missing instead of undercounting", () => {
  const invalid = { ...manifest };
  delete invalid["_vendor.js"];
  assert.throws(() => summarizeBundleGraph(invalid, size), /_vendor\.js/);
  delete invalid["index.html"];
  assert.throws(() => summarizeBundleGraph(invalid, size), /index\.html/);
});
