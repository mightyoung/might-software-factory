import { defineConfig } from "@playwright/test";

const baseURL = "http://127.0.0.1:15213";
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  reporter: "list",
  webServer: {
    command: "npm exec -- vite preview --host 127.0.0.1 --port 15213 --strictPort",
    url: baseURL, reuseExistingServer: false,
  },
  use: {
    baseURL,
    channel: "chrome",
    launchOptions: { args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"] },
  },
});
