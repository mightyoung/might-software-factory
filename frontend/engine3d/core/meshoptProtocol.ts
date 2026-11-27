export const MESHOPT_SOURCE = 1024 * 1024;
export const MESHOPT_JSON = 4096;
export const MESHOPT_INPUT = 28 + MESHOPT_JSON + MESHOPT_SOURCE;
export const MESHOPT_OUTPUT = 28 + MESHOPT_JSON + 65_535 * 12;

export function isMeshoptBytes(input: unknown, limit: number): input is ArrayBuffer {
  return input instanceof ArrayBuffer && input.byteLength >= 28 && input.byteLength <= limit;
}

export type MeshoptRequest = { bytes: ArrayBuffer };
export type MeshoptReply = { ok: true; bytes: ArrayBuffer } | { ok: false };

export function isOwnShape(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  return Boolean(value && typeof value === "object" && Object.keys(value).length === keys.length
    && keys.every((key) => Object.hasOwn(value, key)));
}
