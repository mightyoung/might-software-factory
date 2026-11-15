import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./styles.css";
import { parseAppMode } from "./mode";
import { createEntityLedgerFactory, createFindingStoreFactory, createPermissionAdminFactory, createSessionAdapter } from "./adapters/sessionAdapterFactory";

const mode = parseAppMode(import.meta.env.VITE_API_MODE);
document.title = mode === "http" ? "MIGHT 工厂安全" : "MIGHT 工厂安全 · 演示";
const sessionAdapter = createSessionAdapter(import.meta.env.VITE_API_MODE);
const createFindingStore = createFindingStoreFactory(import.meta.env.VITE_API_MODE);

const createPermissionAdmin = createPermissionAdminFactory(import.meta.env.VITE_API_MODE);
const createEntityLedger = createEntityLedgerFactory(import.meta.env.VITE_API_MODE);

const root = document.getElementById("root");

if (!root) throw new Error("Missing #root element");

createRoot(root).render(
  <StrictMode>
    <App mode={mode} sessionAdapter={sessionAdapter} createFindingStore={createFindingStore} createPermissionAdmin={createPermissionAdmin} createEntityLedger={createEntityLedger} />
  </StrictMode>,
);
