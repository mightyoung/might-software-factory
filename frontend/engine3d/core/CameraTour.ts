export class CameraTour {
  private route: string[] = [];
  private index = -1;
  playing = false;
  replace(route: readonly string[]) {
    this.route = [...new Set(route)].slice(0, 20);
    this.index = -1;
    this.pause();
  }
  current() { return this.route[this.index] ?? null; }
  play() {
    this.index = this.route.length ? 0 : -1;
    this.playing = this.index >= 0;
    return this.current();
  }
  pause() { this.playing = false; }
  select(id: string) {
    const index = this.route.indexOf(id);
    if (index < 0) return false;
    this.index = index;
    this.pause();
    return true;
  }
  advance() {
    if (!this.playing) return null;
    if (this.index + 1 >= this.route.length) {
      this.pause();
      return null;
    }
    this.index++;
    return this.current();
  }
}
