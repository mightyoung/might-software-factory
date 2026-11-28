type Pair = readonly [number, number];

export function projectedResizeDelta(axisPixels: Pair, dragPixels: Pair): number | null {
  if (![...axisPixels, ...dragPixels].every(Number.isFinite)) return null;
  const [x, y] = axisPixels;
  const squared = x * x + y * y;
  if (squared < 36) return null;
  return Math.max(-100, Math.min(100, (x * dragPixels[0] + y * dragPixels[1]) / squared));
}
