import { z } from "zod";
import { MAX_STUDIO_BYTES, parseStudioSnapshot } from "./studioSnapshot.ts";

export const MAX_PLAYER_BYTES = 2 * MAX_STUDIO_BYTES + 4096;
const Player = z.strictObject({
  format: z.literal("might-player-v1"),
  digest: z.string().regex(/^[0-9a-f]{64}$/),
  draft: z.string().min(2).max(MAX_STUDIO_BYTES),
});
const encoder = new TextEncoder();
async function digest(raw: string) {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(raw))),
    (n) => n.toString(16).padStart(2, "0")).join("");
}

export async function createPlayerSnapshot(draft: string): Promise<string> {
  parseStudioSnapshot(draft);
  return JSON.stringify({ format: "might-player-v1", digest: await digest(draft), draft });
}

export async function parsePlayerSnapshot(raw: string) {
  if (raw.length > MAX_PLAYER_BYTES || encoder.encode(raw).length > MAX_PLAYER_BYTES)
    throw new Error("放映文件超过大小限制。");
  let value: unknown;
  try { value = JSON.parse(raw); }
  catch { throw new Error("放映文件不是有效 JSON。"); }
  const parsed = Player.safeParse(value);
  if (!parsed.success) throw new Error("请选择由 Studio 生成的离线放映文件。");
  if (await digest(parsed.data.draft) !== parsed.data.digest) throw new Error("放映文件摘要与内容不一致。");
  return parseStudioSnapshot(parsed.data.draft);
}
