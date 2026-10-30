import { describe, expect, it } from "vitest";
import {
  DRAFT_LIMITS,
  createEmptyDraft,
  formatLocalDateTime,
  hasDraftErrors,
  isBackfill,
  parseLocalDateTime,
  validateFindingDraft,
  type FindingDraft,
} from "./findingDraft";

const NOW = new Date(2026, 9, 30, 14, 30); // 2026-10-30 14:30 本地时间

function valid(overrides: Partial<FindingDraft> = {}): FindingDraft {
  return {
    source: "FIELD_OBSERVATION",
    description: "搬运区隔离设施损坏",
    location: "演示工房 A · 搬运区",
    foundAt: "2026-10-30T14:00",
    urgency: "NORMAL",
    interimMeasure: "",
    ...overrides,
  };
}

describe("本地日期时间", () => {
  it("格式化与严格解析往返一致", () => {
    expect(formatLocalDateTime(NOW)).toBe("2026-10-30T14:30");
    expect(parseLocalDateTime("2026-10-30T14:30")?.getTime()).toBe(NOW.getTime());
  });

  it.each(["", "2026-10-30", "2026-10-30 14:30", "2026-02-31T10:00", "2026-13-01T10:00", "2026-10-30T25:00", "abc"])(
    "拒绝不合法的值 %j",
    (value) => {
      expect(parseLocalDateTime(value)).toBeNull();
    },
  );
});

describe("上报草稿校验", () => {
  it("默认草稿：来源现场发现、紧急程度一般、发现时间为当前时间", () => {
    const draft = createEmptyDraft(NOW);
    expect(draft).toMatchObject({ source: "FIELD_OBSERVATION", urgency: "NORMAL", foundAt: "2026-10-30T14:30" });
  });

  it("合法草稿没有错误", () => {
    expect(validateFindingDraft(valid(), NOW)).toEqual({});
    expect(hasDraftErrors({})).toBe(false);
  });

  it("必填项缺失时逐项给出错误；只含空白也算缺失", () => {
    const errors = validateFindingDraft(valid({ description: "   ", location: "", foundAt: "" }), NOW);
    expect(errors.description).toBe("请填写隐患描述");
    expect(errors.location).toBe("请填写所在位置或设备");
    expect(errors.foundAt).toBe("请填写发现时间");
    expect(hasDraftErrors(errors)).toBe(true);
  });

  it("描述长度按去空白后的字数计，边界值 5 与 500 通过，4 与 501 失败", () => {
    const { descriptionMin, descriptionMax } = DRAFT_LIMITS;
    expect(validateFindingDraft(valid({ description: "字".repeat(descriptionMin) }), NOW).description).toBeUndefined();
    expect(validateFindingDraft(valid({ description: "字".repeat(descriptionMin - 1) }), NOW).description).toMatch(/至少/);
    expect(validateFindingDraft(valid({ description: ` ${"字".repeat(descriptionMax)} ` }), NOW).description).toBeUndefined();
    expect(validateFindingDraft(valid({ description: "字".repeat(descriptionMax + 1) }), NOW).description).toMatch(/不能超过/);
  });

  it("位置与临时措施的长度上限", () => {
    expect(validateFindingDraft(valid({ location: "位".repeat(DRAFT_LIMITS.locationMax + 1) }), NOW).location).toBeDefined();
    expect(
      validateFindingDraft(valid({ interimMeasure: "措".repeat(DRAFT_LIMITS.interimMeasureMax + 1) }), NOW).interimMeasure,
    ).toBeDefined();
    expect(validateFindingDraft(valid({ interimMeasure: "措".repeat(DRAFT_LIMITS.interimMeasureMax) }), NOW)).toEqual({});
  });

  it("发现时间：允许 5 分钟时钟容差，更晚的未来时间被拒绝；格式错误与不存在的日期被拒绝", () => {
    expect(validateFindingDraft(valid({ foundAt: "2026-10-30T14:35" }), NOW).foundAt).toBeUndefined();
    expect(validateFindingDraft(valid({ foundAt: "2026-10-30T14:36" }), NOW).foundAt).toBe("发现时间不能晚于当前时间");
    expect(validateFindingDraft(valid({ foundAt: "2026-10-31T09:00" }), NOW).foundAt).toBe("发现时间不能晚于当前时间");
    expect(validateFindingDraft(valid({ foundAt: "2026-02-31T10:00" }), NOW).foundAt).toBe("发现时间格式不正确");
  });

  it("拒绝不在白名单内的来源和紧急程度（防止被篡改的值通过）", () => {
    const tampered = valid({ source: "MONITORING" as never, urgency: "CRITICAL" as never });
    const errors = validateFindingDraft(tampered, NOW);
    expect(errors.source).toBeDefined();
    expect(errors.urgency).toBeDefined();
  });

  it("草稿类型里没有等级、上报人、租户字段", () => {
    expect(Object.keys(valid()).sort()).toEqual(
      ["description", "foundAt", "interimMeasure", "location", "source", "urgency"].sort(),
    );
  });
});

describe("补报判定", () => {
  it("早于当前时间超过 24 小时才算补报，恰好 24 小时不算", () => {
    expect(isBackfill("2026-10-29T14:30", NOW)).toBe(false);
    expect(isBackfill("2026-10-29T14:29", NOW)).toBe(true);
    expect(isBackfill("2026-10-30T14:00", NOW)).toBe(false);
  });

  it("时间格式不合法时不算补报", () => {
    expect(isBackfill("bad", NOW)).toBe(false);
  });
});
