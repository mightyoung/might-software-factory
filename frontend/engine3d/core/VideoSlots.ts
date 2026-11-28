type Release = () => void;

export class VideoSlots {
  private readonly active = new Map<string, { release: Release; hiddenSince?: number }>();
  private readonly create: (id: string) => Release;
  constructor(create: (id: string) => Release) { this.create = create; }
  ids() { return [...this.active.keys()]; }
  open(id: string, now: number) {
    this.sweep(now);
    const entry = this.active.get(id);
    if (entry) {
      this.active.delete(id);
      this.active.set(id, entry);
      return;
    }
    const first = this.active.keys().next().value;
    if (this.active.size >= 4 && first !== undefined) this.close(first);
    this.active.set(id, { release: this.create(id) });
  }
  setVisible(id: string, visible: boolean, now: number) {
    const entry = this.active.get(id);
    if (entry) entry.hiddenSince = visible ? undefined : entry.hiddenSince ?? now;
  }
  sweep(now: number) {
    for (const [id, { hiddenSince }] of this.active)
      if (hiddenSince !== undefined && now - hiddenSince >= 15000) this.close(id);
  }
  close(id: string) {
    const entry = this.active.get(id);
    if (!entry) return;
    this.active.delete(id);
    entry.release();
  }
  closeAll() { for (const id of this.active.keys()) this.close(id); }
}
