import { defineTheme } from "@astryxdesign/core/theme";

// Reuse MSF’s current light/dark palette, rather than switching product colors.
export const mightTheme = defineTheme({
  name: "might",
  tokens: {
    "--color-accent": "var(--petrol)",
    "--color-on-accent": "var(--white)",
    "--color-text-accent": "var(--petrol)",
    "--color-accent-muted": "var(--tint)",
    "--color-neutral": "var(--surface-soft)",
    "--color-background-surface": "var(--white)",
    "--color-background-body": "var(--paper)",
    "--color-text-primary": "var(--ink)",
    "--color-text-secondary": "var(--muted)",
    "--color-text-disabled": "var(--disabled-ink)",
    "--color-border": "var(--rule)",
    "--font-family-body": "var(--body-face)",
    "--font-family-heading": "var(--body-face)",
    "--font-family-code": "var(--mono)",
    "--radius-element": "3px",
    "--focus-outline-color": "var(--focus)",
    "--focus-outline-offset": "3px",
  },
});
