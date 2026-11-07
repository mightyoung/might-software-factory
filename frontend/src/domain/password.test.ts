import { describe, expect, it } from "vitest";
import { utf8ByteLength, validatePasswordChange } from "./password";

const ok = { current: "old", next: "a".repeat(15), confirm: "a".repeat(15) };

describe("validatePasswordChange", () => {
  it("accepts the boundaries: 15 code points and 72 bytes", () => {
    expect(validatePasswordChange(ok)).toEqual({});
    const edge = "a".repeat(72);
    expect(validatePasswordChange({ ...ok, next: edge, confirm: edge })).toEqual({});
  });

  it("rejects short, and over-72-byte new passwords (bytes, not characters)", () => {
    expect(validatePasswordChange({ ...ok, next: "a".repeat(14), confirm: "a".repeat(14) }).next).toBeDefined();
    const chinese = "密".repeat(25); // 25 个码点但 75 字节
    expect(utf8ByteLength(chinese)).toBe(75);
    expect(validatePasswordChange({ ...ok, next: chinese, confirm: chinese }).next).toMatch(/72 字节/);
    const okChinese = "密".repeat(24); // 72 字节
    expect(validatePasswordChange({ ...ok, next: okChinese, confirm: okChinese })).toEqual({});
  });

  it("counts emoji as one code point for the minimum", () => {
    const emoji = "😀".repeat(15); // 15 码点、60 字节
    expect(validatePasswordChange({ ...ok, next: emoji, confirm: emoji })).toEqual({});
  });

  it("does not trim or normalise: spaces count, and confirmation must match exactly", () => {
    const spaced = " ".repeat(15);
    expect(validatePasswordChange({ ...ok, next: spaced, confirm: spaced })).toEqual({});
    expect(validatePasswordChange({ ...ok, confirm: ok.next + " " }).confirm).toBeDefined();
  });

  it("requires a current password", () => {
    expect(validatePasswordChange({ ...ok, current: "" }).current).toBeDefined();
    expect(validatePasswordChange({ ...ok, current: "x".repeat(73) }).current).toBeDefined();
  });
});
