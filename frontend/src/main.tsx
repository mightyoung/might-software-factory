import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./styles.css";
import { parseAppMode } from "./mode";
import { createFindingStoreFactory, createSessionAdapter } from "./adapters/sessionAdapterFactory";

const mode = parseAppMode(import.meta.env.VITE_API_MODE);
const sessionAdapter = createSessionAdapter(import.meta.env.VITE_API_MODE);
const createFindingStore = createFindingStoreFactory(import.meta.env.VITE_API_MODE);

const root = document.getElementById("root");

if (!root) throw new Error("Missing #root element");

createRoot(root).render(
  <StrictMode>
    <App mode={mode} sessionAdapter={sessionAdapter} createFindingStore={createFindingStore} />
  </StrictMode>,
);
