import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig(({ mode, command }) => ({
  // The browser entry fixes its transport for the lifetime of the page. Propagate
  // it into leaf UI branches so each release omits the other mode's presentation.
  // Development and embedded component tests retain the runtime mode provider.
  define: command === "build" ? {
    "import.meta.env.MSF_BROWSER_MODE": JSON.stringify(loadEnv(mode, ".", "").VITE_API_MODE === "http" ? "http" : "synthetic"),
  } : {},
  plugins: [react()],
  build: {
    // CI uses the import graph to budget entry and deferred dependencies.
    manifest: true,
    // Compact the production output without moving lazy boundaries.
    minify: "terser",
    terserOptions: { compress: { passes: 2 }, maxWorkers: 2 },
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [{
            // This helper is called by both entry and lazy UI chunks. If Rolldown
            // co-locates it with a React consumer, ui-core can import itself
            // indirectly and run that consumer before the React wrapper initializes.
            name: "vite-preload",
            test: /vite\/preload-helper\.js$/,
            includeDependenciesRecursively: false,
          }, {
            // Intl code is shared by Astryx. Keep this vendor chunk free of
            // app components that depend on ui-core itself.
            name: "intl-format",
            tags: ["$initial"],
            test: /node_modules[\\/](?:@formatjs[\\/][^\\/]+|intl-messageformat)[\\/]/,
            includeDependenciesRecursively: false,
          }, {
            // Compress the already-eager Astryx and React modules together. Do not pull
            // page-only modules or their other dependencies into the initial graph.
            name: "ui-core",
            tags: ["$initial"],
            test: /node_modules[\\/](?:@astryxdesign[\\/]core|@stylexjs[\\/]stylex|react|react-dom|scheduler)[\\/]/,
            includeDependenciesRecursively: false,
          }],
        },
      },
    },
  },
  // 仅开发：http 模式下 /api 同源转发到本机后端（Cookie 会话要求同源）。目标默认 127.0.0.1:8080，可用 BACKEND_URL 覆盖。
  server: { proxy: { "/api": loadEnv(mode, ".", "").BACKEND_URL || "http://127.0.0.1:8080" } },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    clearMocks: true,
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
}));
