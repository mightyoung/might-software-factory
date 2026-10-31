import { describe, expect, it } from "vitest";
import {
  dateKey,
  formatDateTime,
  parseWallTime,
  toIsoWithOffset,
  toWallTime,
  wallTimeToIso,
} from "./time";

// 全部使用绝对时刻，结果不应随运行机器的时区变化（CI 与本地用不同 TZ 反复验证过）。
const INSTANT = new Date("2026-10-30T06:30:05Z"); // 上海 14:30:05

describe("业务时间（Asia/Shanghai）", () => {
  it("把绝对时刻换算成上海墙上时间与带偏移 ISO", () => {
    expect(toWallTime(INSTANT)).toBe("2026-10-30T14:30");
    expect(toIsoWithOffset(INSTANT)).toBe("2026-10-30T14:30:05+08:00");
  });

  it("跨日换算：UTC 16:30 已经是上海次日 00:30", () => {
    const instant = new Date("2026-10-30T16:30:00Z");
    expect(toWallTime(instant)).toBe("2026-10-31T00:30");
    expect(dateKey("2026-10-30T16:30:00Z")).toBe("2026-10-31");
  });

  it("墙上时间与绝对时刻往返一致", () => {
    expect(parseWallTime("2026-10-30T14:30")?.toISOString()).toBe("2026-10-30T06:30:00.000Z");
    expect(wallTimeToIso("2026-10-30T14:30")).toBe("2026-10-30T14:30:00+08:00");
  });

  it.each(["", "2026-10-30", "2026-10-30 14:30", "2026-02-31T10:00", "2026-13-01T10:00", "2026-10-30T25:00", "abc"])(
    "拒绝不合法的墙上时间 %j",
    (value) => {
      expect(parseWallTime(value)).toBeNull();
      expect(wallTimeToIso(value)).toBeNull();
    },
  );

  it("展示：带任意偏移的 ISO 都按上海时间显示", () => {
    expect(formatDateTime("2026-10-29T09:14:00+08:00")).toBe("2026-10-29 09:14");
    expect(formatDateTime("2026-10-29T01:14:00Z")).toBe("2026-10-29 09:14");
    expect(formatDateTime("2026-10-28T20:14:00-05:00")).toBe("2026-10-29 09:14");
  });

  it("日期键：同一时刻按上海日期归属，而不是 UTC 日期", () => {
    expect(dateKey("2026-10-29T00:30:00+08:00")).toBe("2026-10-29");
    expect(dateKey("2026-10-28T16:30:00Z")).toBe("2026-10-29");
  });

  it("无法解析的值原样返回，不编造日期", () => {
    expect(formatDateTime("not a date")).toBe("not a date");
    expect(dateKey("not a date")).toBe("not a date");
  });
});
