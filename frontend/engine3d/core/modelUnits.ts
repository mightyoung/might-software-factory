export type SourceUnit = "m" | "mm";

export function inspectPreviewUnits(bounds: readonly [number, number, number], unit: SourceUnit) {
  const factor = unit === "mm" ? 0.001 : 1;
  return { meters: bounds.map((value) => value * factor), checkUnits: unit === "m" && Math.max(...bounds) > 500 };
}
