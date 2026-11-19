import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ThemeProvider, ThemeSelector } from "./ThemeProvider";

let dark = false;
let listeners: Set<() => void>;

beforeEach(() => {
  localStorage.clear();
  dark = false;
  listeners = new Set();
  vi.stubGlobal("matchMedia", vi.fn(() => ({
    get matches() { return dark; },
    addEventListener: (_type: string, callback: () => void) => listeners.add(callback),
    removeEventListener: (_type: string, callback: () => void) => listeners.delete(callback),
  })));
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.astryxTheme;
  document.documentElement.style.colorScheme = "";
});

it("follows system changes, persists explicit choice, and restores system behavior", () => {
  const view = render(<ThemeProvider><ThemeSelector /></ThemeProvider>);
  expect(screen.getByRole("combobox", { name: "主题" })).toHaveValue("system");
  expect(document.documentElement.dataset.theme).toBe("light");
  act(() => { dark = true; listeners.forEach(callback => callback()); });
  expect(document.documentElement.dataset.theme).toBe("dark");
  fireEvent.change(screen.getByRole("combobox", { name: "主题" }), { target: { value: "light" } });
  expect(localStorage.getItem("might.theme")).toBe("light");
  expect(document.documentElement.dataset.theme).toBe("light");
  act(() => { listeners.forEach(callback => callback()); });
  expect(document.documentElement.dataset.theme).toBe("light");
  fireEvent.change(screen.getByRole("combobox", { name: "主题" }), { target: { value: "system" } });
  expect(document.documentElement.dataset.theme).toBe("dark");
  view.unmount();
  expect(listeners.size).toBe(0);
});

it("restores a saved preference and keeps theme switching available when storage is blocked", () => {
  localStorage.setItem("might.theme", "dark");
  const initial = render(<ThemeProvider><ThemeSelector /></ThemeProvider>);
  expect(document.documentElement.dataset.theme).toBe("dark");
  initial.unmount();
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new DOMException("Blocked"); });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("Blocked"); });
  render(<ThemeProvider><ThemeSelector /></ThemeProvider>);
  expect(screen.getByRole("combobox", { name: "主题" })).toHaveValue("system");
  fireEvent.change(screen.getByRole("combobox", { name: "主题" }), { target: { value: "dark" } });
  expect(document.documentElement.dataset.theme).toBe("dark");
  expect(document.documentElement.style.colorScheme).toBe("dark");
});

it("uses the system preference for an invalid stored value", () => {
  localStorage.setItem("might.theme", "invalid");
  dark = true;
  render(<ThemeProvider><ThemeSelector /></ThemeProvider>);
  expect(screen.getByRole("combobox", { name: "主题" })).toHaveValue("system");
  expect(document.documentElement.dataset.theme).toBe("dark");
});
