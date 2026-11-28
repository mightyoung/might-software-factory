import "./styles/layers.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppCore } from "./App";
import "@astryxdesign/core/astryx.css";
import "./styles.css";
import "./shell.css";
import "./operational.css";
import "./astryx-themes";
import "./themes/might/might.fallback.css";
import "./features/theme/astryx-theme.css";
import { ThemeProvider } from "./features/theme/ThemeProvider";
import { parseAppMode } from "./mode";
import { createHttpAppAdapters } from "./adapters/httpAppAdapters";
import { createSyntheticAppAdapters } from "./adapters/syntheticAppAdapters";

const mode = parseAppMode(import.meta.env.VITE_API_MODE);
document.title = mode === "http" ? "MIGHT 工厂安全" : "MIGHT 工厂安全 · 演示";
const adapters = import.meta.env.VITE_API_MODE === "http"
  ? createHttpAppAdapters()
  : createSyntheticAppAdapters();

const root = document.getElementById("root");

if (!root) throw new Error("Missing #root element");

createRoot(root).render(
  <StrictMode>
    <ThemeProvider>
      <AppCore mode={mode} {...adapters} />
    </ThemeProvider>
  </StrictMode>,
);
