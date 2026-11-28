import { defineConfig } from "vite";

export default defineConfig({ base: "./", build: { manifest: true, rolldownOptions: { input: { studio: "index.html", player: "player.html" } } } });
