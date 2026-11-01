import { defineConfig } from "@playwright/test";

// 对着契约模拟服务跑真实浏览器。用本机已装的 Chrome（channel），不下载浏览器；端口避开开发默认的 8080/5173。
const MOCK = "http://127.0.0.1:18080";
const APP = "http://127.0.0.1:15173";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "*.e2e.ts",
  workers: 1, // 模拟服务是共享的内存状态
  fullyParallel: false,
  reporter: "list",
  use: { baseURL: APP, channel: "chrome", trace: "retain-on-failure" },
  webServer: [
    { command: "node tools/mock-server/server.mjs", url: `${MOCK}/api/v1/auth/csrf`, env: { PORT: "18080" }, reuseExistingServer: false },
    {
      command: "npm run dev -- --port 15173 --strictPort",
      url: APP,
      env: { VITE_API_MODE: "http", BACKEND_URL: MOCK },
      reuseExistingServer: false,
    },
  ],
});
