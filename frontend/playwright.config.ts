import { defineConfig } from "@playwright/test";

// 对着契约模拟服务跑真实浏览器。用本机已装的 Chrome（channel），不下载浏览器；端口避开开发默认的 8080/5173。
const MOCK = "http://127.0.0.1:18080";
const APP = "http://127.0.0.1:15173";

const preview = process.env.E2E_PREVIEW;
const synthetic = preview === "synthetic";
const mockServer = {
  command: "node tools/mock-server/server.mjs",
  url: MOCK + "/api/v1/auth/csrf",
  env: { PORT: "18080", MOCK_REVIEW_CYCLES: "1" },
  reuseExistingServer: false,
};

export default defineConfig({
  testDir: "./e2e",
  testMatch: synthetic ? "*.synthetic.e2e.ts" : "*.e2e.ts",
  testIgnore: synthetic ? [] : ["*.synthetic.e2e.ts"],
  workers: 1, // 模拟服务是共享的内存状态
  fullyParallel: false,
  reporter: "list",
  use: { baseURL: APP, channel: "chrome", trace: "retain-on-failure" },
  webServer: [
    ...(synthetic ? [] : [mockServer]),
    {
      command: preview ? "npm exec -- vite preview --host 127.0.0.1 --port 15173 --strictPort" : "npm run dev -- --port 15173 --strictPort",
      url: APP,
      env: synthetic ? {} : { VITE_API_MODE: "http", BACKEND_URL: MOCK },
      reuseExistingServer: false,
    },
  ],
});
