import { MAX_GLB_BYTES, openStaticGlb, type StaticGlb } from "./glbIntake.ts";

type Asset = { id: string; name: string; size: number; metrics: StaticGlb["metrics"] };
const LIMIT = 32 * 1024 * 1024;

export class LocalAssetBank {
  private entries = new Map<string, { asset: Asset; bytes: ArrayBuffer }>();
  private used = 0;
  private readonly maxEntries: number;
  constructor(maxEntries = 8) {
    if (!Number.isInteger(maxEntries) || maxEntries < 1 || maxEntries > 8) throw new RangeError("模型库容量不合法。");
    this.maxEntries = maxEntries;
  }

  async register(rawName: string, data: ArrayBuffer): Promise<Asset> {
    const name = rawName.trim();
    if (!name.toLowerCase().endsWith(".glb") || name.length > 128 || data.byteLength > MAX_GLB_BYTES)
      throw new Error("GLB：需要大小和名称符合要求的本地模型。");
    const bytes = data.slice(0);
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const id = Array.from(hash, (b) => b.toString(16).padStart(2, "0")).join("");
    const prior = this.entries.get(id);
    if (prior) return structuredClone(prior.asset);
    if (this.entries.size >= this.maxEntries || this.used + bytes.byteLength > LIMIT)
      throw new Error("当前会话模型库容量已满，请先移除一个模型。");
    const model = openStaticGlb(bytes);
    try {
      const asset = { id, name, size: bytes.byteLength, metrics: model.metrics };
      this.entries.set(id, { asset, bytes });
      this.used += bytes.byteLength;
      return structuredClone(asset);
    } finally {
      model.dispose();
    }
  }

  list() { return [...this.entries.values()].map(({ asset }) => structuredClone(asset)); }
  read(id: string) { return this.entries.get(id)?.bytes.slice(0) ?? null; }
  remove(id: string) {
    const entry = this.entries.get(id);
    if (!entry) return false;
    this.entries.delete(id);
    this.used -= entry.bytes.byteLength;
    return true;
  }
}
