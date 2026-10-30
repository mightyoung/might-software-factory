export const LOGIN_PATH = "/login";

export interface ParsedPath {
  /** 路径中的租户；没有租户前缀时为 null。 */
  tenantId: string | null;
  /** 去掉租户前缀与首尾斜杠后的应用内路径，如 "safety/findings"。 */
  rest: string;
}

function trimSlashes(value: string): string {
  return value.replace(/^\/+|\/+$/g, "");
}

/** 租户进入路由，避免多标签页依赖可变的“当前租户”会话状态而串租户。 */
export function tenantPath(tenantId: string, rest: string): string {
  const tail = trimSlashes(rest);
  return `/t/${encodeURIComponent(tenantId)}${tail ? `/${tail}` : ""}`;
}

export function parsePath(pathname: string): ParsedPath {
  const match = /^\/t\/([^/]+)(?:\/(.*))?$/.exec(pathname);
  if (!match) return { tenantId: null, rest: trimSlashes(pathname) };
  try {
    return { tenantId: decodeURIComponent(match[1]), rest: trimSlashes(match[2] ?? "") };
  } catch {
    // 畸形的百分号编码：视为没有合法租户前缀，由调用方按未授权处理。
    return { tenantId: null, rest: trimSlashes(pathname) };
  }
}

/**
 * 登录后的返回地址只接受本站相对路径：属于当前租户的，或尚无租户前缀的旧地址（登录后由 App 规范化）。
 * 拒绝绝对地址、协议相对地址（//host）、反斜杠和控制字符，防止开放重定向；其他租户的地址一律拒绝。
 */
export function safeNextPath(raw: string | null, tenantId: string): string | null {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//")) return null;
  if (raw.includes("\\") || /[\u0000-\u001f\u007f]/.test(raw)) return null;
  const pathname = raw.split(/[?#]/)[0] ?? "";
  if (pathname === LOGIN_PATH) return null;
  const parsed = parsePath(pathname);
  if (parsed.rest === "") return null;
  if (parsed.tenantId !== null && parsed.tenantId !== tenantId) return null;
  return raw;
}

export function loginPathWithNext(location: string): string {
  const pathname = location.split(/[?#]/)[0] ?? "";
  if (pathname === "/" || pathname === LOGIN_PATH) return LOGIN_PATH;
  return `${LOGIN_PATH}?next=${encodeURIComponent(location)}`;
}
