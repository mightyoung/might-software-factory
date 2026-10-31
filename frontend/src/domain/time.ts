/**
 * 业务时间约定（来自后端对 issue #8 的建议，尚无已验证的租户时区配置）：
 * - 界面一律按 Asia/Shanghai 展示，不依赖浏览器所在时区；
 * - 与后端交换的时间是带偏移的 ISO 8601（本前端发送 +08:00），不发送无时区字符串；
 * - 表单里的日期时间输入（datetime-local）按上海时间的“墙上时间” YYYY-MM-DDTHH:mm 理解。
 *
 * 上海自 1991 年起没有夏令时，固定 +08:00 与该时区一致，因此用固定偏移换算，行为不随机器时区变化。
 */
export const DISPLAY_TIME_ZONE = "Asia/Shanghai";
export const DISPLAY_OFFSET = "+08:00";
const OFFSET_MS = 8 * 3_600_000;

const WALL_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** 把时刻平移到上海墙上时间，之后用 getUTC* 读取各个字段。 */
function shifted(date: Date): Date {
  return new Date(date.getTime() + OFFSET_MS);
}

/** 上海墙上时间 YYYY-MM-DDTHH:mm（用于 datetime-local 的值和 max）。 */
export function toWallTime(date: Date): string {
  const s = shifted(date);
  return `${s.getUTCFullYear()}-${pad(s.getUTCMonth() + 1)}-${pad(s.getUTCDate())}T${pad(s.getUTCHours())}:${pad(s.getUTCMinutes())}`;
}

/** 严格解析上海墙上时间；格式不符或日期不存在（如 2 月 31 日）返回 null。 */
export function parseWallTime(value: string): Date | null {
  const match = WALL_TIME.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number) as [number, number, number, number, number];
  const instant = new Date(Date.UTC(year, month - 1, day, hour, minute) - OFFSET_MS);
  const s = shifted(instant);
  const roundTrips =
    s.getUTCFullYear() === year &&
    s.getUTCMonth() === month - 1 &&
    s.getUTCDate() === day &&
    s.getUTCHours() === hour &&
    s.getUTCMinutes() === minute;
  return roundTrips ? instant : null;
}

/** 带偏移的 ISO 8601，秒精度，例如 2026-10-30T14:30:05+08:00。 */
export function toIsoWithOffset(date: Date): string {
  const s = shifted(date);
  return `${toWallTime(date)}:${pad(s.getUTCSeconds())}${DISPLAY_OFFSET}`;
}

/** 上海墙上时间 → 带偏移 ISO；不合法返回 null。 */
export function wallTimeToIso(value: string): string | null {
  const instant = parseWallTime(value);
  return instant ? `${value}:00${DISPLAY_OFFSET}` : null;
}

/** 带偏移的 ISO → 上海时间 "YYYY-MM-DD HH:mm"。无法解析时原样返回，不编造。 */
export function formatDateTime(iso: string): string {
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) return iso;
  return toWallTime(instant).replace("T", " ");
}

/** 带偏移的 ISO → 上海日期 "YYYY-MM-DD"（日期筛选和日期列的边界）。无法解析时原样返回。 */
export function dateKey(iso: string): string {
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) return iso;
  return toWallTime(instant).slice(0, 10);
}
