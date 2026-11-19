import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState, type ReactNode } from "react";

export type ThemePreference = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";
const storageKey = "might.theme";
const systemQuery = "(prefers-color-scheme: dark)";

interface ThemeState {
  preference: ThemePreference;
  resolvedTheme: ResolvedTheme;
  setPreference(value: ThemePreference): void;
}

const ThemeContext = createContext<ThemeState>({
  preference: "system",
  resolvedTheme: "light",
  setPreference() {},
});

function validPreference(value: string | null): value is ThemePreference {
  return value === "light" || value === "dark" || value === "system";
}

function savedPreference(): ThemePreference {
  try {
    const saved = localStorage.getItem(storageKey);
    return validPreference(saved) ? saved : "system";
  } catch {
    return "system";
  }
}

function systemDark(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia(systemQuery).matches;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreference] = useState<ThemePreference>(savedPreference);
  const [darkSystem, setDarkSystem] = useState(systemDark);
  const resolvedTheme: ResolvedTheme = preference === "system" ? (darkSystem ? "dark" : "light") : preference;

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia(systemQuery);
    const update = () => setDarkSystem(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.dataset.astryxTheme = "might";
    document.documentElement.style.colorScheme = resolvedTheme;
  }, [resolvedTheme]);

  useEffect(() => {
    try {
      localStorage.setItem(storageKey, preference);
    } catch {
      // The active theme remains available when browser storage is restricted.
    }
  }, [preference]);

  const value = useMemo(() => ({ preference, resolvedTheme, setPreference }), [preference, resolvedTheme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  return useContext(ThemeContext);
}

export function ThemeSelector() {
  const { preference, setPreference } = useTheme();
  return <label className="theme-selector">
    <span>主题</span>
    <select aria-label="主题" value={preference} onChange={event => {
      if (validPreference(event.target.value)) setPreference(event.target.value);
    }}>
      <option value="light">浅色</option>
      <option value="dark">深色</option>
      <option value="system">跟随系统</option>
    </select>
  </label>;
}
