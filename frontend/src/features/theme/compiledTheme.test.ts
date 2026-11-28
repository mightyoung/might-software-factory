import { expect, it } from "vitest";
import { mightTheme } from "../../themes/might/might.js";
import entry from "../../main.tsx?raw";

it("uses the compiled Astryx module and compatibility aliases after declaring cascade order", () => {
  expect(entry.startsWith('import "./styles/layers.css";')).toBe(true);
  expect(entry).toContain('import "./astryx-themes";');
  expect(entry).toContain('import "./themes/might/might.fallback.css";');
  expect(mightTheme.name).toBe("might");
  expect(mightTheme.tokens["--color-accent"]).toBe("var(--petrol)");
  expect(mightTheme.tokens["--color-text-primary"]).toBe("var(--ink)");
});
