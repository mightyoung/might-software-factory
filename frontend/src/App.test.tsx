import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { App } from "./App";

function setPath(path: string) {
  window.history.replaceState(null, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

describe("应用路由与导航", () => {
  beforeEach(() => setPath("/safety/findings"));
  afterEach(() => {
    cleanup();
    setPath("/safety/findings");
  });

  it("在安全应用内通过侧栏切换路由，并可切换顶部应用", () => {
    render(<App />);
    expect(screen.getByRole("heading", { name: "隐患台账" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("link", { name: "排查任务" }));
    expect(screen.getByRole("heading", { name: "排查任务" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/safety/inspections");

    fireEvent.click(screen.getByRole("link", { name: "设备管理" }));
    expect(screen.getByRole("heading", { name: "设备管理" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/equipment/overview");
  });
});
