import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => ({
  plugins: [react()],
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
