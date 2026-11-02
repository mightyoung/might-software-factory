import { describe, expect, it } from "vitest";
import { classifyHttpError } from "./classifyError";
import { ApiError } from "./httpClient";

describe("classifyHttpError", () => {
  it.each([
    [400, "invalid"], [401, "unauthenticated"], [403, "forbidden"], [404, "notVisible"], [409, "conflict"],
    [422, "workflow"], [429, "rateLimited"], [500, "unavailable"], [503, "unavailable"], [0, "unavailable"],
  ])("%i → %s", (status, expected) => {
    expect(classifyHttpError(new ApiError(status, "X", null, false))).toBe(expected);
  });
});
