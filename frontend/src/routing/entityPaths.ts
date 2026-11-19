const KEY = "[a-z][a-z0-9_-]{0,63}";
const ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const ROUTE = new RegExp(`^apps/(${KEY})/entities/(${KEY})(?:/(create)|/records/(${ID})(/edit)?)?$`);
export interface EntityRoute { appKey: string; entityKey: string; base: string; page: "entry" | "create" | "detail" | "edit"; id?: string }
export function parseEntityRoute(rest: string): EntityRoute | null {
  const match = ROUTE.exec(rest);
  if (!match) return null;
  return { appKey: match[1]!, entityKey: match[2]!, base: `apps/${match[1]}/entities/${match[2]}`, page: match[3] ? "create" : match[4] ? (match[5] ? "edit" : "detail") : "entry", ...(match[4] ? { id: match[4] } : {}) };
}
export function applicationPath(app: string, path: string): boolean {
  const route = parseEntityRoute(path);
  return route !== null && route.appKey === app && route.page === "entry";
}
