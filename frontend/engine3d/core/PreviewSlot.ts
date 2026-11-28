import type { StaticGlb } from "./glbIntake.ts";

/** The only owner of the currently displayed model; the caller owns unaccepted candidates. */
export class PreviewSlot {
  current: StaticGlb | null = null;
  replace(value: StaticGlb) {
    if (this.current === value) return;
    this.clear();
    this.current = value;
  }
  clear() {
    const previous = this.current;
    this.current = null;
    previous?.dispose();
  }
}
