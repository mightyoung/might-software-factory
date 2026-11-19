import { defineConfig } from "vite";
export default defineConfig({
  base: "./",
  build: {
    outDir: "../public/office",
    emptyOutDir: true,
    rolldownOptions: { input: { sheets: "index.html", docs: "docs.html" } },
  },
});
